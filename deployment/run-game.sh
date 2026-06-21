#!/bin/sh
set -eu

mkdir -p log
sleep 8

# Datapack scripts are loose classes. They must be visible to both the
# in-memory compiler and the runtime class loader.
exec java -Xms512m -Xmx2g \
	-cp "l2jserver.jar:script:libs/*" \
	com.l2jserver.gameserver.GameServer
