#!/bin/bash

workplace=$(pwd)
#outputpath=$workplace/out

echo "$(date): Workplace path: $workplace"

./gradlew assembleDebug

# Copy artifacts
echo "$(date): Copy artifacts"
mkdir -p $outputpath
cp vector/build/outputs/apk/fdroid/debug/*.apk $outputpath/



