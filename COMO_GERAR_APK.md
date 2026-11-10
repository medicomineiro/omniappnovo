# Como gerar o APK do OmniCase AI pelo celular

1. Envie o **conteúdo** deste ZIP à raiz do repositório GitHub `medicomineiro/omniappotimizado` (não apenas o arquivo ZIP).
2. Confira se o arquivo `.github/workflows/build-apk.yml` está no repositório. No navegador do celular, é possível criá-lo em **Add file > Create new file** e colar seu conteúdo.
3. Abra **Actions > Compilar OmniCase AI APK > Run workflow > Run workflow**.
4. Aguarde o resultado. Na execução concluída, em **Artifacts**, selecione **OmniCase-AI-APK**.
5. Descompacte o download para obter `app-debug.apk` e instale-o no Android. Permita a instalação de apps dessa origem quando solicitado.

## Observações

- O workflow gera APK **debug**, assinado automaticamente pelo Android SDK, adequado para instalação e testes. Não é uma versão de publicação para a Play Store.
- Se houver uma instalação anterior com o mesmo `applicationId` e assinatura incompatível, o Android poderá exigir a desinstalação antes da instalação (faça backup dos dados antes).
- O projeto original usa `applicationIdSuffix = ".debug"` no build debug, por isso esta versão pode ser instalada ao lado da versão release.
- Para diagnóstico: Actions > execução com falha > Compilar APK de teste instalavel. Envie o log da etapa se falhar.
- Nenhuma chave de API deve ser colocada no repositório público nem embutida no APK: prefira configurar cada chave no aplicativo, ciente das implicações de segurança ao executar requisições diretamente do dispositivo.
