Place a compatible Geyser standalone/core jar here as `Geyser-Standalone.jar`
for compiling the bridge module, or build with:

`mvn package -Dgeyser.standalone.jar=C:\path\to\Geyser-Standalone.jar`

The jar is a provided compile dependency only. Runtime classes come from the
Geyser instance that loads the extension.
