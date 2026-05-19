# AA Proxy Media Tap Viewer

A tiny standalone Android client for `aa-proxy-rs` media taps.

It does **not** use the companion reverse proxy.

Flow:

1. Fetch `GET http://10.0.0.1/media-taps`
2. Read each endpoint's `direct_port`
3. Connect directly to `tcp://10.0.0.1:<direct_port>`
4. Expose that TCP stream as local HTTP
5. Play `http://127.0.0.1:<random>/stream.ts` with Media3 ExoPlayer

## HUD / heads-up mode

The player has a `Mirror` toggle. It horizontally flips the video so the display can be reflected on glass.

## Player controls

The player screen is fullscreen. Tap the video to show or hide the top controls. The top bar contains Back, Mirror, and Reconnect.

## Expected `/media-taps` fields

```json
{
  "endpoint_id": "display:aux-1",
  "inject_display_id": "aux-1",
  "label": "video-aux",
  "kind": "video",
  "display_type": "DISPLAY_TYPE_AUXILIARY",
  "port": 13002,
  "direct_port": 12347
}
```

`direct_port` is used by this app.

## Build

Open this folder in Android Studio and run the `app` configuration.

The default host is:

```text
10.0.0.1
```

## Notes

- Video endpoints are expected to be MPEG-TS/H264.
- Audio endpoints may be raw PCM and may not be playable through ExoPlayer without wrapping.
- Android Auto must already be connected so that aa-proxy-rs has processed SDR and opened media tap ports.
