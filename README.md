# TCG Scanner (Riftbound)

App Android (Kotlin + Jetpack Compose) que escanea cartas con la cámara (OCR de ML Kit),
consulta el precio en CardTrader, las guarda en un carrito y luego en una colección permanente.

## Compilar el APK con GitHub Actions
1. Crea un repositorio en GitHub (puede ser privado) y sube TODO el contenido de esta carpeta (incluida `.github/`).
2. Pestaña **Actions** > workflow **Build APK** (se lanza solo al subir a `main`, o con *Run workflow*).
3. Al terminar, en el resumen del run descarga el artefacto **tcg-scanner-debug-apk** (un zip con `app-debug.apk`).
4. Pásalo al móvil, permite "instalar apps desconocidas" e instala.

## Primer uso
1. Ajustes > pega tu token JWT de CardTrader > Guardar token. (El token NO va en el código ni en el repo.)
2. Ajustes > Descargar catálogo (tarda unos segundos por el límite de 1 petición/segundo).
3. Vuelve al escáner y enseña una carta: debe verse el nombre nítido. Si el OCR falla, usa "Buscar".
