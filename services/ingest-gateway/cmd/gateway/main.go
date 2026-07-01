package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type config struct {
	HTTPAddr      string
	DatabaseURL   string
	IngestAPIKey  string
	BatchSize     int
	FlushInterval time.Duration
	QueueSize     int
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

	go g.batchWriter(ctx)

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
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("http shutdown failed", "error", err)
	}
}

func loadConfig() (config, error) {
	cfg := config{
		HTTPAddr:      getenv("HTTP_ADDR", ":8081"),
		DatabaseURL:   os.Getenv("DATABASE_URL"),
		IngestAPIKey:  os.Getenv("INGEST_API_KEY"),
		BatchSize:     getenvInt("BATCH_SIZE", 64),
		FlushInterval: getenvDuration("FLUSH_INTERVAL", time.Second),
		QueueSize:     getenvInt("QUEUE_SIZE", 2048),
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
	}

	return cfg, nil
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
	if decoder.More() {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "only one event per request is allowed"})
		return
	}
	if err := event.Validate(); err != nil {
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

func (e attendanceEvent) Validate() error {
	switch {
	case e.RoomID < 1:
		return errors.New("room_id must be positive")
	case e.Timestamp.IsZero():
		return errors.New("ts is required")
	case e.Timestamp.After(time.Now().Add(5 * time.Minute)):
		return errors.New("ts cannot be more than five minutes in the future")
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

func (g *gateway) batchWriter(ctx context.Context) {
	ticker := time.NewTicker(g.cfg.FlushInterval)
	defer ticker.Stop()

	batch := make([]attendanceEvent, 0, g.cfg.BatchSize)
	flush := func() {
		if len(batch) == 0 {
			return
		}
		if err := g.writeBatch(context.Background(), batch); err != nil {
			g.logger.Error("write attendance batch", "events", len(batch), "error", err)
		} else {
			g.logger.Info("attendance batch written", "events", len(batch))
		}
		batch = batch[:0]
	}

	for {
		select {
		case <-ctx.Done():
			for {
				select {
				case event := <-g.events:
					batch = append(batch, event)
				default:
					flush()
					return
				}
			}
		case event := <-g.events:
			batch = append(batch, event)
			if len(batch) >= g.cfg.BatchSize {
				flush()
			}
		case <-ticker.C:
			flush()
		}
	}
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
