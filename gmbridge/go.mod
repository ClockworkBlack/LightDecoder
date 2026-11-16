module lightdecoder.local/gmbridge

go 1.26.0

toolchain go1.26.11

require (
	github.com/google/uuid v1.6.0
	github.com/rs/zerolog v1.35.1
	go.mau.fi/mautrix-gmessages v0.2610.0
	go.mau.fi/util v0.11.1
)

require (
	filippo.io/edwards25519 v1.2.0 // indirect
	github.com/beeper/argo-go v1.1.2 // indirect
	github.com/coder/websocket v1.8.15 // indirect
	github.com/elliotchance/orderedmap/v3 v3.1.0 // indirect
	github.com/mattn/go-colorable v0.1.14 // indirect
	github.com/mattn/go-isatty v0.0.20 // indirect
	github.com/mattn/go-sqlite3 v1.14.53 // indirect
	github.com/vektah/gqlparser/v2 v2.5.27 // indirect
	go.mau.fi/libsignal v0.2.3 // indirect
	go.mau.fi/whatsmeow v0.0.0-20261116124925-2ecca2c913b9 // indirect
	golang.org/x/crypto v0.59.0 // indirect
	golang.org/x/exp v0.0.0-20261113113407-e3970619a1a4 // indirect
	golang.org/x/mobile v0.0.0-20261011034528-197770444867 // indirect
	golang.org/x/mod v0.43.0 // indirect
	golang.org/x/net v0.62.0 // indirect
	golang.org/x/sync v0.25.0 // indirect
	golang.org/x/sys v0.50.0 // indirect
	golang.org/x/text v0.44.0 // indirect
	golang.org/x/tools v0.52.1-0.20261106145633-d407941a339a // indirect
	google.golang.org/protobuf v1.36.12 // indirect
)

tool (
	golang.org/x/mobile/cmd/gobind
	golang.org/x/mobile/cmd/gomobile
)

// A patched copy of libgm (pkg/libgm only); see third_party/mautrix-gmessages/PATCHES.md.
replace go.mau.fi/mautrix-gmessages => ./third_party/mautrix-gmessages
