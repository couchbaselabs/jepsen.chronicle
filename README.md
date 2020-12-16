# jepsen.chronicle

Jepsen testing for Chronicle

### Requirements

A JVM and the [Leiningen](https://leiningen.org/) build tool need to be
installed.

### Getting Started

The provision script can be used to automatically start suitable VMs.
```
./provision.sh --action=create --nodes=3
```

A copy of the Chronicle source code is then required
```
git clone https://github.com/couchbase/chronicle.git chronicle
```

You can then invoke leiningen to run a test
```
lein trampoline run test --nodes-file ./nodes --username vagrant --password vagrant --concurrency 30 --workload register --install ./chronicle
```
Note that for subsequent runs the `--install ./chronicle` parameter can be omitted to avoid recompiling chronicle on each run. The --workload controls which nemesis is applied to the nodes, see the help text under `lein run test --help` for the full list of options.

After the tests are completed the vagrant VMs can be torn down with
```
./provision.sh --action=destroy-all
```
