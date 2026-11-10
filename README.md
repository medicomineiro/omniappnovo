# OmniCase AI — Android

Discussão de casos com múltiplas IAs (Gemini, OpenAI, Claude, OpenRouter, Groq/compatível) e análise de imagens.

## Gerar o APK

### Opção A — GitHub Actions (sem instalar nada)
1. Crie um repositório (pode ser privado) e envie esta pasta.
2. Aba **Actions** → *Build APK* → **Run workflow** (também roda a cada push em `main`).
3. Ao terminar, baixe o artefato **OmniCase-AI-release** → `app-release.apk`.

Sem secrets, o APK sai assinado com chave debug (instala via sideload, não vai para a Play Store).
Para assinatura própria, crie os secrets `KEYSTORE_BASE64` (`base64 -w0 sua.jks`), `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

### Opção B — Android Studio
Abrir a pasta → aceitar a criação do Gradle Wrapper → *Build ▸ Build APK(s)*.
Requer JDK 17+ e SDK Platform 36.1.

### Opção C — CLI (Ubuntu)
```bash
# com Android cmdline-tools e Gradle 9.3.1 instalados
export ANDROID_HOME=$HOME/Android/Sdk
gradle :app:assembleRelease
# saída: app/build/outputs/apk/release/app-release.apk
```

## Uso
Ajustes → inserir chave de cada provedor. O campo **Modelo** é editável: digite qualquer ID novo
lançado pelo provedor sem recompilar. Provedores sem chave são ignorados na junta.
