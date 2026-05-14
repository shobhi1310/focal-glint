# Focal

Focal is an Android app that turns notifications into local topics and summaries using on-device Gemma models.

## Install

1. Install the release APK from this repository's Releases page.

```bash
adb install focal-0.1.0-release.apk
```

2. Open `Tune -> Developer Settings` in Focal and complete the built-in setup:

- Grant Notification Access
- Disable battery optimization
- Keep the selected model as `Gemma 4 E2B`
- Tap `Download` to fetch `gemma-4-E2B-it.litertlm`
- Tap `Start` to load the model

3. Accept the [Gemma terms](https://ai.google.dev/gemma/terms).

4. Download the embedding files:

- [`embeddinggemma-300M_seq1024_mixed-precision.tflite`](https://huggingface.co/litert-community/embeddinggemma-300m/blob/main/embeddinggemma-300M_seq1024_mixed-precision.tflite)
- [`sentencepiece.model`](https://huggingface.co/litert-community/embeddinggemma-300m/blob/main/sentencepiece.model)

5. Copy both files to the path Focal checks:

   ```bash
   adb shell mkdir -p /sdcard/Android/data/com.focal/files/models/embeddings
   adb push embeddinggemma-300M_seq1024_mixed-precision.tflite /sdcard/Android/data/com.focal/files/models/embeddings/
   adb push sentencepiece.model /sdcard/Android/data/com.focal/files/models/embeddings/
   ```

6. Reopen Focal. `Embedding Model` should show `Model found`.

EmbeddingGemma is not downloadable inside Developer Settings yet because access requires accepting the Gemma terms first.
