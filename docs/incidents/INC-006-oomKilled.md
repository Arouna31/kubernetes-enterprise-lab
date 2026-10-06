# INC-006 — Container OOMKilled

## Incident Summary

A dedicated test workload was created to intentionally exceed its Kubernetes memory limit.

The objective was to reproduce and diagnose a real `OOMKilled` container failure without impacting the `booking-api` application.

---

## Environment

```text
Namespace: booking-dev
Workload: oom-test
Container: memory-hog
Image: python:3.12-alpine
```

The test workload is isolated from the production-like application Pods.

---

## Test Workload

The following Pod was created:

```yaml
apiVersion: v1
kind: Pod

metadata:
  name: oom-test
  namespace: booking-dev

  labels:
    app: oom-test

spec:
  restartPolicy: Always

  containers:
    - name: memory-hog
      image: python:3.12-alpine

      resources:
        requests:
          memory: "32Mi"
          cpu: "10m"

        limits:
          memory: "64Mi"
          cpu: "100m"

      command:
        - python
        - -c
        - |
          import time

          print("Allocating 200 MiB...")
          data = bytearray(200 * 1024 * 1024)

          print("Allocation completed")
          time.sleep(3600)
```

---

## Fault Injection

The container memory limit is:

```text
64Mi
```

The Python process attempts to allocate:

```text
200Mi
```

Therefore:

```text
requested memory
      |
      v
200Mi
      |
      v
limit = 64Mi
      |
      v
memory limit exceeded
```

---

## Expected Failure

The expected behaviour is:

```text
Container starts
      |
      v
Python allocates memory
      |
      v
memory limit exceeded
      |
      v
OOMKilled
      |
      v
container restarted
```

---

## Pod Observation

The Pod was monitored using:

```bash
kubectl get pod oom-test -n booking-dev -w
```

The status can transition rapidly between states such as:

```text
Running
OOMKilled
Running
CrashLoopBackOff
```

The `OOMKilled` state may be short-lived and not always visible directly in `kubectl get`.

---

## Reliable Diagnosis

The Pod was inspected using:

```bash
kubectl describe pod oom-test -n booking-dev
```

The previous container state revealed:

```text
Last State:
  Terminated:
    Reason: OOMKilled
    Exit Code: 137
```

---

## Exit Code 137

The observed exit code was:

```text
137
```

This typically means the process was terminated by `SIGKILL`.

Conceptually:

```text
128 + signal number 9
= 137
```

The kernel terminated the process because the container exceeded its memory constraint.

---

## Direct Status Query

The termination reason can be extracted directly:

```bash
kubectl get pod oom-test \
  -n booking-dev \
  -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}'
```

Expected:

```text
OOMKilled
```

Exit code:

```bash
kubectl get pod oom-test \
  -n booking-dev \
  -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}'
```

Expected:

```text
137
```

---

## Restart Behaviour

Because:

```yaml
restartPolicy: Always
```

Kubernetes restarts the container after the failure.

Repeated failures produce:

```text
OOMKilled
   |
restart
   |
OOMKilled
   |
restart
   |
...
```

Eventually Kubernetes introduces a restart backoff.

---

## CrashLoopBackOff

Repeated crashes can result in:

```text
CrashLoopBackOff
```

Important distinction:

```text
OOMKilled
= why the previous container terminated
```

while:

```text
CrashLoopBackOff
= Kubernetes delaying repeated restarts
```

These statuses describe different aspects of the failure.

---

## Previous Logs

When a container has restarted, logs from the previous instance can be accessed using:

```bash
kubectl logs oom-test \
  -n booking-dev \
  --previous
```

This is particularly useful in production when the current container has already restarted.

---

## Events

Namespace events can be inspected using:

```bash
kubectl get events \
  -n booking-dev \
  --sort-by=.metadata.creationTimestamp
```

Events provide additional lifecycle and restart information.

---

## Troubleshooting Model

When a Pod shows repeated restarts:

```text
Pod restarts increasing
       |
       v
kubectl describe pod
       |
       v
Last State
       |
       +--> OOMKilled
              |
              v
       inspect memory limit
              |
              v
       inspect real memory usage
```

Then investigate possible causes:

```text
memory limit too low
memory leak
unexpected traffic spike
large in-memory workload
JVM heap configuration
native memory usage
```

---

## CPU vs Memory Behaviour

CPU and memory limits have different failure modes.

CPU:

```text
CPU limit exceeded
      |
      v
throttling
```

Memory:

```text
Memory limit exceeded
      |
      v
OOMKilled
```

Memory limits therefore have a more destructive runtime effect.

---

## JVM Consideration

For JVM workloads such as Spring Boot, memory consumption is not limited to the Java heap.

Relevant areas include:

```text
Java Heap
Metaspace
Thread stacks
Direct buffers
Native memory
JIT structures
```

A container memory limit must therefore leave enough space for the full JVM process, not only `-Xmx`.

---

## Cleanup

After validating the incident:

```bash
kubectl delete -f k8s/scenarios/oom-test.yml
```

The YAML remains versioned in Git so the incident can be reproduced later.

---

## Key Learnings

- `OOMKilled` means the container exceeded available memory.
- Exit code `137` is a strong signal of `SIGKILL`.
- A Pod can currently be `Running` even if the container crashed previously.
- `RESTARTS` is an important diagnostic signal.
- `Last State` often contains the real cause of the previous failure.
- `kubectl logs --previous` is critical for restarted containers.
- `CrashLoopBackOff` describes restart throttling, not the original root cause.
- CPU limits and memory limits behave differently.
- JVM memory sizing must account for heap and native memory.
- Failure scenarios should preferably be reproduced on isolated test workloads.

---

## Resolution Status

```text
✅ Memory limit configured
✅ Memory allocation exceeded limit
✅ OOMKilled reproduced
✅ Exit code 137 observed
✅ Container restart observed
✅ CrashLoopBackOff behaviour understood
✅ Previous logs inspected
✅ Test workload isolated from booking-api
✅ Test workload cleaned up
```

**Status: RESOLVED**
