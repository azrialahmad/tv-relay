# Bench

Measures download speed on the TV itself (run with `app_process`), reading the stream URL from a file so API keys
never appear on a command line or in output.

```sh
javac --release 8 -d out Bench.java
d8 --min-api 26 --output out out/Bench.class          # Android SDK build-tools
adb push out/classes.dex /data/local/tmp/bench/
# put the stream URL in /data/local/tmp/bench/direct.url (chmod 600), and the relay-wrapped
# http://127.0.0.1:7000/play/x.mkv?u=<base64url of it> in relay.url, then:
adb shell 'CLASSPATH=/data/local/tmp/bench/classes.dex app_process /data/local/tmp Bench 25 1 /data/local/tmp/bench/direct.url 20'
adb shell 'CLASSPATH=/data/local/tmp/bench/classes.dex app_process /data/local/tmp Bench 25 1 /data/local/tmp/bench/relay.url 25'
adb shell 'CLASSPATH=/data/local/tmp/bench/classes.dex app_process /data/local/tmp Bench 25 4 /data/local/tmp/bench/direct.url 30'
adb shell 'rm /data/local/tmp/bench/*.url'
```
Args: seconds, connections, URL file, start offset in GB (keep it below the file size). Delete the URL files after.
