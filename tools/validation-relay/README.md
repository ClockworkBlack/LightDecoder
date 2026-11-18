# Patched ValidationRelay (for Blue Bubbles)

`lightdecoder.patch` applies to [JJTech0130/ValidationRelay](https://github.com/JJTech0130/ValidationRelay)
at commit `e1b74fbc92a1664dd4fafec526f59acd8f5e6425`, the iPhone app OpenBubbles uses as its relay.
`.github/workflows/validation-relay.yml` builds it on a macOS runner into `ValidationRelay-LightDecoder.tipa`
(an artifact kept 3 days) for installing with TrollStore on the owner's own iPhone. It is not released.

What the patch changes:
- Validation data is made off the main thread with time limits, so the relay keeps answering the
  relay server's pings while Apple is asked, and a failure is logged instead of crashing the app
  (the original uses `try!`, `assert` and force-unwraps throughout).
- Plain-HTTP access to `static.ess.apple.com` is allowed (App Transport Security exception), with
  an HTTPS fallback.
- The registration secret is no longer written to the Event Log.
- Structured log: every event also goes to `Documents/relay-log.jsonl`, one JSON object per line
  (`t`, `ms` since start, `lvl` I/W/E, `ev`, `msg`, plus fields). Crashes are caught into it (signal
  handlers write `CRASH ...` lines; uncaught exceptions with a stack), a run that did not end cleanly is
  recorded as `app.unclean_exit`, and background/foreground and network changes are recorded.
- The Event Log's share button shares `relay-report.txt`: a summary (device, websocket connects,
  errors by kind, validation requests/sent/failed by reason, crashes) followed by every event.

Reading a report: `val.requested` with no `val.sent`/`val.failed` after it means the app died or was
suspended mid-validation (look for `app.background`, `CRASH`, `app.unclean_exit`); `val.failed`
`reason` says which step failed (`cert_fetch`, `nac_init`, `init_validation`, `nac_key`, `nac_sign`,
`timeout`, `network`, `http`); `ws.error` `error` gives the network error (POSIX 96 = ENODATA, the
connection was closed under the app); `val.step` `step_ms` shows which step was slow.
