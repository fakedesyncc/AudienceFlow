FROM golang:1.25-alpine AS build

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/ingest-gateway ./cmd/gateway

FROM alpine:3.22

RUN addgroup -S audienceflow && adduser -S audienceflow -G audienceflow
USER audienceflow

COPY --from=build /out/ingest-gateway /usr/local/bin/ingest-gateway

EXPOSE 8081
ENTRYPOINT ["ingest-gateway"]
