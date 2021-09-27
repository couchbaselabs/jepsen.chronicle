# jepsen.chronicle

This repository contains Jepsen tests for Chronicle. Its structure is very similar to that of the [jepsen.couchbase](https://github.com/couchbaselabs/jepsen.couchbase) repository; some code components, such as the sequential checker and parts of the provisioning and vagrant scripts are duplicated between the two repositories. Therefore, the [README](https://github.com/couchbaselabs/jepsen.couchbase/blob/master/README.md) for that repository may also be useful. In future, the shared components might be merged out into their own repository to avoid duplication.

## Getting Started

### Requirements

A JVM and the [Leiningen](https://leiningen.org/) build tool need to be
installed.

### Running Tests

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
lein trampoline run test --nodes-file ./nodes --username vagrant --password vagrant --concurrency 30 --time-limit 30 --workload register --install ./chronicle
```
Note that for subsequent runs the `--install ./chronicle` parameter can be omitted to avoid recompiling chronicle on each run. The --workload controls which nemesis is applied to the nodes, see the help text under `lein run test --help` for the full list of options.

After the tests are completed the vagrant VMs can be torn down with
```
./provision.sh --action=destroy-all
```

### Running Test Suites

A "suite" of tests to be run sequentially can be defined as a Clojure EDN file. These can then be executes as follows
```
lein trampoline run test-all --nodes-file ./nodes --username vagrant --password vagrant --suite ./suites/example.edn --concurrency 30
```

Options provided on the command line apply to all tests in the suites. Options defined in the suite's test maps override options provided on the command line.


## Documentation

### Workloads

Each test contains a workload to be run. The workload consists primarily of a nemesis and a combined generator for client and nemesis operations. The provided generators are designed to generate operations indefinitely, with the test concluding after a preset time-limit is exceeded.

Workloads are defined as functions that take the test options map and returns a map of additional elements to merge into the test map. Each workload function is registered in the chronicle.workload/workloads-map such that is can be loaded during test construction.

Currently there are 9 workloads, of which 7 follow a very simply nemesis action pattern targeting a single node at a time:

* register: No nemesis actions
* freeze: Freezes then unfreezes single node at a time
* crash: Crashes then restarts a single node at a time
* addremove: Removes, wipes and re-adds a single node at a time
* partition: Partitions a single node completely then recovers
* disk: Simulates a disk failure, then stops the node, repairs the disk and restarts
* failover: Fails over one node, wipes and re-adds it

* majorityfailover: The majority failover workload fails over a majority of nodes, then wipes and re-adds them.

* mixed:
The mixed workload allows for more interesting behaviour. It currently combines node removal, node crashes, node freezes and network partitions. It can disrupt multiple nodes at the same time, as well as applying multiple failures to a single node. Since it is configured to only disrupt a minority of nodes at a time such that operations can still be processed, this workload must be run with at least 3 nodes, and requires 5 or more for simultaneous failures on multiple nodes.

### Suites

Test "suites" are a predefined collection of tests that can be defined in configuration files and run using the "test-all" command.

These configuration files are Clojure EDN files containing a vector of maps, where each map represents a test to be run as part of the suite. For each test, the given map is merged into the test options after CLI parsing of command line arguments. There is currently no validation of these maps, so arbitrary values can be injected for testing purposes.

### Client Generator

The client generator is responsible for generating the operations to be performed. The behaviour of the client generator is controlled by the client-type test parameter. Two client types are defined:

* :reg-gen generates a mixture of read and (unique) write operations across independent keys
* :txn-gen generates transactions consisting of read and (random) write operations

The chosen client generator also determines the checker used. Register operations are checked using both the Knossos linearizability checker with a standard register model and our experimental sequential checker. Transaction are checked using Knossos linearizability checker with the multi-register model.

### Nemeses

Nemeses are provided to perform node removals, crashes, freezes, disk failures, and network partitions.

The test map contains a :membership key, which consists of an atom mapping each node to a hash-set. This value is used to track the current expected status of nodes in the cluster. Upon each nemesis disruption, a corresponding key is inserted into the hash-set of the affected node until the disruption is repaired. Thus, an empty set represents a healthy node.

This tracking is required to ensure that operations are sent to suitable cluster members when multiple failures are applied to a cluster. Many nemesis operations may fail if all nodes are being disrupted simultaneously, but workloads should generally not need to do this.
