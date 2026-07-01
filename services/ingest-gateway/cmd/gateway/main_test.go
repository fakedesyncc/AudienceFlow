package main

import (
	"context"
	"io"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"
)

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func validEvent(now time.Time) attendanceEvent {
	return attendanceEvent{
		RoomID:     1,
		Timestamp:  now,
		Count:      5,
		Confidence: 0.9,
		WorkerID:   "worker-1",
	}
}

func TestValidate(t *testing.T) {
	now := time.Date(2026, 7, 1, 12, 0, 0, 0, time.UTC)
	maxAge := 24 * time.Hour

	tests := []struct {
		name    string
		mutate  func(e *attendanceEvent)
		wantErr bool
	}{
		{
			name:    "valid",
			mutate:  func(e *attendanceEvent) {},
			wantErr: false,
		},
		{
			name:    "missing room_id",
			mutate:  func(e *attendanceEvent) { e.RoomID = 0 },
			wantErr: true,
		},
		{
			name:    "missing ts",
			mutate:  func(e *attendanceEvent) { e.Timestamp = time.Time{} },
			wantErr: true,
		},
		{
			name:    "future ts beyond skew",
			mutate:  func(e *attendanceEvent) { e.Timestamp = now.Add(10 * time.Minute) },
			wantErr: true,
		},
		{
			name:    "future ts within skew allowed",
			mutate:  func(e *attendanceEvent) { e.Timestamp = now.Add(2 * time.Minute) },
			wantErr: false,
		},
		{
			name:    "stale ts beyond max age",
			mutate:  func(e *attendanceEvent) { e.Timestamp = now.Add(-25 * time.Hour) },
			wantErr: true,
		},
		{
			name:    "old ts within max age allowed",
			mutate:  func(e *attendanceEvent) { e.Timestamp = now.Add(-23 * time.Hour) },
			wantErr: false,
		},
		{
			name:    "negative count",
			mutate:  func(e *attendanceEvent) { e.Count = -1 },
			wantErr: true,
		},
		{
			name:    "confidence too high",
			mutate:  func(e *attendanceEvent) { e.Confidence = 1.5 },
			wantErr: true,
		},
		{
			name:    "confidence negative",
			mutate:  func(e *attendanceEvent) { e.Confidence = -0.1 },
			wantErr: true,
		},
		{
			name:    "worker_id too short",
			mutate:  func(e *attendanceEvent) { e.WorkerID = "ab" },
			wantErr: true,
		},
		{
			name:    "worker_id too long",
			mutate:  func(e *attendanceEvent) { e.WorkerID = strings.Repeat("x", 129) },
			wantErr: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			e := validEvent(now)
			tc.mutate(&e)
			err := e.Validate(now, maxAge)
			if tc.wantErr && err == nil {
				t.Fatalf("expected error, got nil")
			}
			if !tc.wantErr && err != nil {
				t.Fatalf("expected no error, got %v", err)
			}
		})
	}
}

func TestAuthorized(t *testing.T) {
	key := strings.Repeat("k", 32)
	g := &gateway{cfg: config{IngestAPIKey: key}}

	tests := []struct {
		name   string
		header string
		want   bool
	}{
		{name: "valid key", header: key, want: true},
		{name: "invalid key", header: "wrong-key-value-1234567890", want: false},
		{name: "empty key", header: "", want: false},
		{name: "prefix of key", header: key[:16], want: false},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := g.authorized(tc.header); got != tc.want {
				t.Fatalf("authorized(%q) = %v, want %v", tc.header, got, tc.want)
			}
		})
	}
}

func TestDefaultMaxBuffer(t *testing.T) {
	tests := []struct {
		batchSize int
		want      int
	}{
		{batchSize: 1, want: 1000},
		{batchSize: 50, want: 1000},
		{batchSize: 64, want: 1280},
		{batchSize: 100, want: 2000},
	}
	for _, tc := range tests {
		if got := defaultMaxBuffer(tc.batchSize); got != tc.want {
			t.Fatalf("defaultMaxBuffer(%d) = %d, want %d", tc.batchSize, got, tc.want)
		}
	}
}

// flakyWriter fails its first failUntil calls, then succeeds, recording every
// batch it was asked to persist.
type flakyWriter struct {
	mu        sync.Mutex
	calls     int
	failUntil int
	written   [][]attendanceEvent
	err       error
}

func (f *flakyWriter) write(_ context.Context, events []attendanceEvent) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	if f.calls <= f.failUntil {
		return f.err
	}
	// Copy to snapshot exactly what was persisted at this point.
	snapshot := make([]attendanceEvent, len(events))
	copy(snapshot, events)
	f.written = append(f.written, snapshot)
	return nil
}

// TestFlushRetentionInvariant proves that when writeBatch fails the events are
// RETAINED (not dropped) and are written on a subsequent successful flush.
func TestFlushRetentionInvariant(t *testing.T) {
	now := time.Date(2026, 7, 1, 12, 0, 0, 0, time.UTC)
	fw := &flakyWriter{failUntil: 1, err: io.ErrUnexpectedEOF}
	g := &gateway{
		cfg:          config{BatchSize: 8, MaxBuffer: 100},
		logger:       testLogger(),
		writeBatchFn: fw.write,
		now:          func() time.Time { return now },
	}

	batch := []attendanceEvent{validEvent(now), validEvent(now)}
	// Use a near-zero backoff to avoid slowing the test.
	backoff := time.Nanosecond

	// First flush fails: batch must be retained in full.
	batch = g.attemptFlush(batch, &backoff)
	if len(batch) != 2 {
		t.Fatalf("after failed flush, retained batch len = %d, want 2", len(batch))
	}
	if len(fw.written) != 0 {
		t.Fatalf("expected nothing written after failed flush, got %d", len(fw.written))
	}

	// Second flush succeeds: batch cleared and both events persisted.
	batch = g.attemptFlush(batch, &backoff)
	if len(batch) != 0 {
		t.Fatalf("after successful flush, batch len = %d, want 0", len(batch))
	}
	if len(fw.written) != 1 || len(fw.written[0]) != 2 {
		t.Fatalf("expected one successful write of 2 events, got %#v", fw.written)
	}
}

// TestEnforceBufferDropsOldest verifies the bounded buffer drops the oldest
// overflow when the retained batch exceeds MaxBuffer.
func TestEnforceBufferDropsOldest(t *testing.T) {
	g := &gateway{cfg: config{MaxBuffer: 3}, logger: testLogger()}

	batch := []attendanceEvent{
		{RoomID: 1}, {RoomID: 2}, {RoomID: 3}, {RoomID: 4}, {RoomID: 5},
	}
	got := g.enforceBuffer(batch)
	if len(got) != 3 {
		t.Fatalf("enforceBuffer len = %d, want 3", len(got))
	}
	// Oldest (RoomID 1,2) dropped; most recent retained.
	if got[0].RoomID != 3 || got[2].RoomID != 5 {
		t.Fatalf("enforceBuffer kept wrong events: %#v", got)
	}
}

// TestDrainFlushRetriesThenSucceeds verifies the shutdown drain retries a
// transient failure and eventually persists the batch.
func TestDrainFlushRetriesThenSucceeds(t *testing.T) {
	now := time.Now()
	fw := &flakyWriter{failUntil: 1, err: io.ErrUnexpectedEOF}
	g := &gateway{
		cfg:          config{},
		logger:       testLogger(),
		writeBatchFn: fw.write,
		now:          func() time.Time { return now },
	}

	g.drainFlush([]attendanceEvent{validEvent(now)})
	if len(fw.written) != 1 {
		t.Fatalf("drainFlush persisted %d batches, want 1", len(fw.written))
	}
}
