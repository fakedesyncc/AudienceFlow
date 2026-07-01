package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	// flushBackoffBase is the initial delay between failed flush retries.
	flushBackoffBase = 200 * time.Millisecond
	// flushBackoffCap bounds the exponential backoff between failed flushes.
	flushBackoffCap = 5 * time.Second
	// shutdownDrainTimeout bounds the final drain flush during shutdown.
	shutdownDrainTimeout = 8 * time.Second
)

type config struct {
	HTTPAddr      string
	DatabaseURL   string
	IngestAPIKey  string
	BatchSize     int
	FlushInterval time.Duration
	QueueSize     int
	MaxBuffer     int
	MaxEventAge   time.Duration
}

type attendanceEvent struct {
	RoomID     int       `json:"room_id"`
	Timestamp  time.Time `json:"ts"`
	Count      int       `json:"count"`
	Confidence float64   `json:"confidence"`
	WorkerID   string    `json:"worker_id"`
}

type gateway struct {
	cfg    config
	db     *pgxpool.Pool
	events chan attendanceEvent
	logger *slog.Logger
	// writeBatchFn persists a batch. It defaults to (*gateway).writeBatch but
	// is overridable in tests so the batch writer can be exercised without a DB.
	writeBatchFn func(ctx context.Context, events []attendanceEvent) error
	// now returns the current time; overridable in tests.
	now func() time.Time
}

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	cfg, err := loadConfig()
	if err != nil {
		logger.Error("invalid configuration", "error", err)
		os.Exit(1)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	db, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		logger.Error("connect database", "error", err)
		os.Exit(1)
	}
	defer db.Close()

	if err := db.Ping(ctx); err != nil {
		logger.Error("ping database", "error", err)
		os.Exit(1)
	}

	g := &gateway{
		cfg:    cfg,
		db:     db,
		events: make(chan attendanceEvent, cfg.QueueSize),
		logger: logger,
	}
	g.writeBatchFn = g.writeBatch
	g.now = time.Now

	var writerWG sync.WaitGroup
	writerWG.Add(1)
	go func() {
		defer writerWG.Done()
		g.batchWriter()
	}()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", g.health)
	mux.HandleFunc("GET /readyz", g.ready)
	mux.HandleFunc("POST /v1/events", g.ingestEvent)

	server := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           requestLogger(logger, recoverer(mux)),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
	}

	go func() {
		logger.Info("ingest gateway listening", "addr", cfg.HTTPAddr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("http server failed", "error", err)
			stop()
		}
	}()

	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	// Fully quiesce HTTP handlers first so no producer can enqueue events after
	// the events channel is closed, then close the channel and wait for the
	// batch writer to finish its final drain.
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("http shutdown failed", "error", err)
	}
	close(g.events)
	writerWG.Wait()
}

func loadConfig() (config, error) {
	cfg := config{
		HTTPAddr:      getenv("HTTP_ADDR", ":8081"),
		DatabaseURL:   os.Getenv("DATABASE_URL"),
		IngestAPIKey:  os.Getenv("INGEST_API_KEY"),
		BatchSize:     getenvInt("BATCH_SIZE", 64),
		FlushInterval: getenvDuration("FLUSH_INTERVAL", time.Second),
		QueueSize:     getenvInt("QUEUE_SIZE", 2048),
		MaxBuffer:     getenvInt("MAX_BUFFER", 0),
		MaxEventAge:   getenvDuration("MAX_EVENT_AGE", 24*time.Hour),
	}

	if cfg.MaxBuffer <= 0 {
		cfg.MaxBuffer = defaultMaxBuffer(cfg.BatchSize)
	}

	switch {
	case cfg.DatabaseURL == "":
		return cfg, errors.New("DATABASE_URL is required")
	case len(cfg.IngestAPIKey) < 24:
		return cfg, errors.New("INGEST_API_KEY must be at least 24 characters")
	case cfg.BatchSize < 1:
		return cfg, errors.New("BATCH_SIZE must be positive")
	case cfg.QueueSize < cfg.BatchSize:
		return cfg, errors.New("QUEUE_SIZE must be greater than or equal to BATCH_SIZE")
	case cfg.MaxBuffer < cfg.BatchSize:
		return cfg, errors.New("MAX_BUFFER must be greater than or equal to BATCH_SIZE")
	case cfg.MaxEventAge <= 0:
		return cfg, errors.New("MAX_EVENT_AGE must be positive")
	}

	return cfg, nil
}

// defaultMaxBuffer bounds the in-memory retry buffer to max(BatchSize*20, 1000).
func defaultMaxBuffer(batchSize int) int {
	if buf := batchSize * 20; buf > 1000 {
		return buf
	}
	return 1000
}

func (g *gateway) health(w http.ResponseWriter, r *http.Request) {
	if err := g.db.Ping(r.Context()); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unhealthy"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (g *gateway) ready(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status":          "ready",
		"queued_events":   len(g.events),
		"queue_capacity":  cap(g.events),
		"batch_size":      g.cfg.BatchSize,
		"flush_interval":  g.cfg.FlushInterval.String(),
		"ingest_security": "header-key",
	})
}

func (g *gateway) ingestEvent(w http.ResponseWriter, r *http.Request) {
	if !g.authorized(r.Header.Get("X-Ingest-Key")) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid ingest key"})
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, 16*1024)
	defer r.Body.Close()

	var event attendanceEvent
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&event); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid json payload"})
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "only one event per request is allowed"})
		return
	}
	if err := event.Validate(g.now(), g.cfg.MaxEventAge); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}

	select {
	case g.events <- event:
		writeJSON(w, http.StatusAccepted, map[string]any{
			"status": "queued",
			"room":   event.RoomID,
			"ts":     event.Timestamp.UTC().Format(time.RFC3339),
		})
	default:
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "ingest queue is full"})
	}
}

func (g *gateway) authorized(header string) bool {
	if header == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(header), []byte(g.cfg.IngestAPIKey)) == 1
}

func (e attendanceEvent) Validate(now time.Time, maxAge time.Duration) error {
	switch {
	case e.RoomID < 1:
		return errors.New("room_id must be positive")
	case e.Timestamp.IsZero():
		return errors.New("ts is required")
	case e.Timestamp.After(now.Add(5 * time.Minute)):
		return errors.New("ts cannot be more than five minutes in the future")
	case maxAge > 0 && e.Timestamp.Before(now.Add(-maxAge)):
		return errors.New("ts is too far in the past")
	case e.Count < 0:
		return errors.New("count must be non-negative")
	case e.Confidence < 0 || e.Confidence > 1:
		return errors.New("confidence must be between 0 and 1")
	case len(strings.TrimSpace(e.WorkerID)) < 3:
		return errors.New("worker_id must be at least 3 characters")
	case len(e.WorkerID) > 128:
		return errors.New("worker_id must be at most 128 characters")
	default:
		return nil
	}
}

// batchWriter consumes events until the events channel is closed, batching
// them and flushing to the database. On a failed flush the batch is retained
// (not dropped) and retried on the next flush; only a successful write clears
// it. It returns after draining and flushing all remaining events once the
// channel is closed.
func (g *gateway) batchWriter() {
	ticker := time.NewTicker(g.cfg.FlushInterval)
	defer ticker.Stop()

	// batch holds events accepted but not yet durably written. It persists
	// across flush failures so accepted attendance is never dropped on a
	// transient DB error.
	batch := make([]attendanceEvent, 0, g.cfg.BatchSize)
	backoff := flushBackoffBase

	// flush attempts to persist the current batch. On success it clears the
	// batch and resets backoff. On failure it retains the batch, enforces the
	// bounded buffer by dropping the oldest overflow, and grows the backoff.
	flush := func() {
		batch = g.attemptFlush(batch, &backoff)
	}

	for {
		select {
		case event, ok := <-g.events:
			if !ok {
				// Channel closed: perform the final bounded drain flush.
				g.drainFlush(batch)
				return
			}
			batch = append(batch, event)
			if len(batch) >= g.cfg.BatchSize {
				flush()
			}
		case <-ticker.C:
			flush()
		}
	}
}

// attemptFlush writes the batch once. On success it returns an empty slice and
// resets backoff. On failure it returns the retained (bounded) batch and applies
// a capped exponential backoff sleep so a DB outage does not spin.
func (g *gateway) attemptFlush(batch []attendanceEvent, backoff *time.Duration) []attendanceEvent {
	if len(batch) == 0 {
		return batch
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	err := g.writeBatchFn(ctx, batch)
	cancel()
	if err == nil {
		g.logger.Info("attendance batch written", "events", len(batch))
		*backoff = flushBackoffBase
		return batch[:0]
	}

	g.logger.Error("write attendance batch", "events", len(batch), "error", err)
	batch = g.enforceBuffer(batch)

	// Capped exponential backoff between failed flushes.
	time.Sleep(*backoff)
	if next := *backoff * 2; next < flushBackoffCap {
		*backoff = next
	} else {
		*backoff = flushBackoffCap
	}
	return batch
}

// enforceBuffer bounds the retained batch to cfg.MaxBuffer, dropping the oldest
// overflow (and logging a warning) to avoid unbounded memory growth.
func (g *gateway) enforceBuffer(batch []attendanceEvent) []attendanceEvent {
	if g.cfg.MaxBuffer <= 0 || len(batch) <= g.cfg.MaxBuffer {
		return batch
	}
	overflow := len(batch) - g.cfg.MaxBuffer
	g.logger.Warn("attendance buffer overflow, dropping oldest", "count", overflow)
	// Drop the oldest events, keeping the most recent MaxBuffer.
	return append(batch[:0], batch[overflow:]...)
}

// drainFlush performs the final shutdown flush with a bounded deadline. It
// retries on failure until the deadline; if it still cannot persist the batch
// it logs at error level with the count rather than silently dropping data.
func (g *gateway) drainFlush(batch []attendanceEvent) {
	if len(batch) == 0 {
		return
	}
	deadline := g.now().Add(shutdownDrainTimeout)
	backoff := flushBackoffBase
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			break
		}
		ctx, cancel := context.WithTimeout(context.Background(), remaining)
		err := g.writeBatchFn(ctx, batch)
		cancel()
		if err == nil {
			g.logger.Info("attendance batch written", "events", len(batch))
			return
		}
		g.logger.Error("drain flush failed, retrying", "events", len(batch), "error", err)
		if time.Until(deadline) <= backoff {
			break
		}
		time.Sleep(backoff)
		if next := backoff * 2; next < flushBackoffCap {
			backoff = next
		} else {
			backoff = flushBackoffCap
		}
	}
	g.logger.Error("attendance events lost on shutdown drain", "events", len(batch))
}

func (g *gateway) writeBatch(ctx context.Context, events []attendanceEvent) error {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	rows := make([][]any, 0, len(events))
	for _, event := range events {
		rows = append(rows, []any{
			event.RoomID,
			event.Timestamp.UTC(),
			event.Count,
			event.Confidence,
			event.WorkerID,
		})
	}

	_, err := g.db.CopyFrom(
		ctx,
		pgx.Identifier{"attendance"},
		[]string{"room_id", "ts", "count", "confidence", "worker_id"},
		pgx.CopyFromRows(rows),
	)
	return err
}

func requestLogger(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		next.ServeHTTP(w, r)
		logger.Info("http request",
			"method", r.Method,
			"path", r.URL.Path,
			"duration_ms", time.Since(started).Milliseconds(),
			"remote_addr", r.RemoteAddr,
		)
	})
}

func recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "internal server error"})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func getenv(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func getenvInt(key string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func getenvDuration(key string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		fmt.Fprintf(os.Stderr, "invalid duration %s=%q, using %s\n", key, value, fallback)
		return fallback
	}
	return parsed
}
