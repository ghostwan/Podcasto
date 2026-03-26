#!/bin/bash
# Forward port 8080 from the connected Android device to localhost
# Usage: ./portforward.sh [start|stop]

ADB="$ANDROID_HOME/platform-tools/adb"
PORT=8080

case "${1:-start}" in
    start)
        $ADB forward tcp:$PORT tcp:$PORT
        echo "Port forward actif: http://localhost:$PORT"
        ;;
    stop)
        $ADB forward --remove tcp:$PORT
        echo "Port forward supprimé"
        ;;
    *)
        echo "Usage: $0 [start|stop]"
        exit 1
        ;;
esac
