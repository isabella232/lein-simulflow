# lein-simulflow [![Travis CI status](https://secure.travis-ci.org/metosin/lein-simulflow.png)](http://travis-ci.org/#!/metosin/lein-simulflow/builds)

```
“Daydreaming is the first awakening of what we call simulflow. It is
an essential tool of rational thought. With it you can clear the mind for
better thinking.”
```
– Frank Herbert, _Heretics of Dune_

## Usage

Put `[lein-simulflow "0.1.0-SNAPSHOT"]` into the `:plugins` vector of your
project.clj.

```bash
$ lein simulflow
```

## Overview

While doing modern project with both Clojure and ClojureScript your workflow
might require running several lein tasks parallal, e.g. cljx, midje, cljsbuild.
Simulflow will run your tasks in one lein-process. The tasks will run parallel
where possible, for example the cljsbuild depends on files writen by cljx so
it will wait for cljx to complete first.

Originally each of the tasks would run their own loop to watch for file
changes. When using simulflow there is only one file change watcher:
the simulflow. The watch is implemented by using
[dirwatch](https://github.com/juxt/dirwatch) which uses Java 7
provided `java.nio.file` API which delegates to OS provided APIs
(e.g. inotify on Linux) instead of polling
for changes every 100 ms or so.

For notes about implementation and how this relates to other, similar,
plugins check [this](./docs/notes.md).

## TODO

- Test how well the `java.nio.file.WatchService` works for OS X
