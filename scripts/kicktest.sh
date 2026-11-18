#!/bin/bash
# Harvest Hand kick test: a local vanilla server (offline, flat) that starts again whenever it stops, and the client
# test (runKickTest) against it: kicked, then the server restarting; it must reconnect and carry on each time.
cd "$(dirname "$0")/.."
SRV=run-kick-server
JAR=$HOME/.gradle/caches/fabric-loom/1.21.11/minecraft-server.jar
mkdir -p $SRV run-kick
rm -rf $SRV/world $SRV/logs run-kick/kicktest.done
# Local test server only: the Minecraft EULA (https://aka.ms/MinecraftEULA) applies.
echo "eula=true" > $SRV/eula.txt
cat > $SRV/server.properties <<'P'
online-mode=false
enforce-secure-profile=false
server-port=25601
level-type=minecraft\:flat
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
max-players=2
motd=Harvest Hand kick test
P
echo '[{"uuid":"d155728b-9a0f-3b6d-b57d-2702ef2da73a","name":"HhKick","level":4,"bypassesPlayerLimit":false}]' > $SRV/ops.json
(
	while [ ! -f run-kick/kicktest.done ]; do
		(cd $SRV && java -Xmx640m -jar "$JAR" nogui < /dev/null)
		sleep 2
	done
) > $SRV/loop.log 2>&1 &
LOOP=$!
for i in $(seq 1 120); do grep -q "Done (" $SRV/logs/latest.log 2>/dev/null && break; sleep 1; done
./gradlew --no-daemon runKickTest
touch run-kick/kicktest.done
wait $LOOP
