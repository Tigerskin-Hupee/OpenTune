#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
# Licensed under the Apache License, Version 2.0.
#

APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit

APP_BASE_NAME=${0##*/}
APP_HOME=$( cd "$APP_HOME" && pwd -P ) || exit

MAX_FD=maximum

warn () { echo "$*"; } >&2
die () { echo; echo "$*"; echo; exit 1; } >&2

cygwin=false
darwin=false
msys=false
nonstop=false
case "$( uname )" in
  CYGWIN* )  cygwin=true  ;;
  Darwin* )  darwin=true  ;;
  MSYS* | MINGW* ) msys=true ;;
  NONSTOP* ) nonstop=true ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD=java
    command -v java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command found in PATH."
fi

if ! "$cygwin" && ! "$darwin" && ! "$nonstop" ; then
    case $MAX_FD in
      max*) MAX_FD=$( ulimit -H -n ) || warn "Could not query max file descriptor limit" ;;
    esac
    case $MAX_FD in
      '' | soft) ;;
      *) ulimit -n "$MAX_FD" || warn "Could not set max file descriptor limit to $MAX_FD" ;;
    esac
fi

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

save () {
    for i do printf %s\\n "$i" | sed "s/'/'\\\\''/g;1s/^/'/;\$s/\$/'/" ; done
    echo " "
}
APP_ARGS=$(save "$@")

eval set -- $DEFAULT_JVM_OPTS '"$JAVA_OPTS" "$GRADLE_OPTS"' \
    '"-Dorg.gradle.appname=$APP_BASE_NAME"' \
    '-classpath "$CLASSPATH"' \
    'org.gradle.wrapper.GradleWrapperMain' \
    '"$APP_ARGS"'

exec "$JAVACMD" "$@"
