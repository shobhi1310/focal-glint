# Focal

Focal is an Android notification intelligence app. It reads local notifications, groups related items, and uses on-device Gemma models to turn notification noise into focused topics and summaries.

## Install

1. Download the APK from this repository's Releases page.

2. Install the APK on your Android device.

   ```bash
   adb install focal-0.1.0-release.apk
   ```

3. Open Focal and complete the setup screen:

   - grant Notification Access
   - disable battery optimization when Android asks
   - keep the selected model as `Gemma 4 E2B`
   - tap `Download` to download `gemma-4-E2B-it.litertlm`
   - tap `Start` to load the model

4. Accept the Gemma terms before downloading the embedding files:

   https://ai.google.dev/gemma/terms

5. Download these two EmbeddingGemma files from Hugging Face:

   - `embeddinggemma-300M_seq1024_mixed-precision.tflite`
     `https://huggingface.co/litert-community/embeddinggemma-300m/blob/main/embeddinggemma-300M_seq1024_mixed-precision.tflite`
   - `sentencepiece.model`
     `https://huggingface.co/litert-community/embeddinggemma-300m/blob/main/sentencepiece.model`

6. Copy both files to the exact path Focal checks:

   ```bash
   adb shell mkdir -p /sdcard/Android/data/com.focal/files/models/embeddings
   adb push embeddinggemma-300M_seq1024_mixed-precision.tflite /sdcard/Android/data/com.focal/files/models/embeddings/
   adb push sentencepiece.model /sdcard/Android/data/com.focal/files/models/embeddings/
   ```

7. Reopen Focal. The setup screen should show `Model found` under `Embedding Model`.

There is no EmbeddingGemma download button in Developer Settings yet because users must accept Google's Gemma terms before accessing those files.
