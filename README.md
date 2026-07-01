# Spatial Album

Turn any photo into an explorable 3D scene - spatial reframing on Android.

## What It Is

Spatial Album is a native Android app for browsing a spatial photo album, importing your own photos, and reframing them in 3D. Tap **Reframe** to lift a flat photo into a 3D Gaussian-splat scene rendered on-device, drag/orbit the scene, then use Difix refine and FLUX.1-Fill generate to produce and download a final reframed image.

## Download / Install

Download the signed APK from the GitHub Releases page.

1. Transfer the APK to your Android device.
2. Allow installation from unknown sources when Android prompts.
3. Install and open **Spatial Album**.

The app needs internet access. The public APK talks to a hosted demo GPU backend.

## Features

- Curated spatial album with animated 3D-preview thumbnails.
- Import photos from the device gallery.
- Reframe a single image into a 3DGS scene rendered on-device with a hand-written GLES3 splat renderer.
- Bounded drag-to-orbit interaction for controlled spatial reframing.
- Difix refine plus FLUX.1-Fill generate for the final reframed image.
- Local 1 GB splat cache for recently opened scenes.
- Long-press edit/delete mode for removing photos from the in-app album.

## How It Works

The hosted backend runs SHARP single-image reconstruction to produce roughly 1.18M Gaussian splats per image, Difix for novel-view refinement, and FLUX.1-Fill for peripheral completion. The demo backend currently runs on a vast.ai GPU host.

On-device, Spatial Album uses a native GLES3 anisotropic Gaussian-splat rasterizer and the refine/generate pipeline:

- Album and source media are fetched from the backend.
- Splat streams are cached locally and rendered by the Android GLES3 viewer.
- Drag release captures the rendered view and sends it to Difix.
- Generate sends the refined result to FLUX.1-Fill and displays the completed image.

## Backend Note

The public APK points at a hosted demo backend so anyone can try the app without setting up GPU services. This is a demo endpoint and may change.

## License

WiCi-authored source code is provided under MIT terms, but the working product
depends on third-party AI models with non-commercial / research-only /
evaluation-only restrictions. The Spatial Album product and backend should be
treated as non-commercial research/evaluation/demo software unless the operator
obtains separate commercial rights or replaces the restricted model components.

See [LICENSE](LICENSE) and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
