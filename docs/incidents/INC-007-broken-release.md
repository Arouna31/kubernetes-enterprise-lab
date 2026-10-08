# INC-007 — Broken Release and ImagePullBackOff

## Incident Summary

A new `booking-api` release referenced a container image that did not exist.

Kubernetes could not pull the image, causing the new Pod to fail while the previous application revision remained available.

---

## Environment

```text
Namespace: booking-dev
Deployment: booking-api
Strategy: RollingUpdate
Stable image: kube-lab/booking-api:0.2.0
Broken image: kube-lab/booking-api:0.3.0-broken
```

---

## Fault Injection

The Deployment image was deliberately changed to:

```yaml
image: kube-lab/booking-api:0.3.0-broken
```

The image was intentionally unavailable.

The manifest was applied:

```bash
kubectl apply -f k8s/base/booking-api.yml
```

---

## Symptoms

A new Pod was created for the new Deployment revision.

The Pod could not start successfully.

Typical states included:

```text
ErrImagePull
```

followed by:

```text
ImagePullBackOff
```

Meanwhile the previous stable Pods remained:

```text
1/1 Running
```

---

## Investigation

The failing Pod was inspected:

```bash
kubectl describe pod <broken-pod> -n booking-dev
```

Relevant events indicated image retrieval failure.

Conceptually:

```text
Kubernetes node
     |
     | pull image
     v
kube-lab/booking-api:0.3.0-broken
     |
     X
image unavailable
```

---

## ErrImagePull vs ImagePullBackOff

These states represent different phases.

```text
ErrImagePull
```

means Kubernetes attempted to retrieve the image and failed.

After repeated failures:

```text
ImagePullBackOff
```

means Kubernetes is delaying additional image pull attempts.

Conceptually:

```text
pull image
   |
   X
ErrImagePull
   |
retry
   |
   X
ErrImagePull
   |
   v
ImagePullBackOff
```

---

## Events

Events were inspected with:

```bash
kubectl get events \
  -n booking-dev \
  --sort-by=.metadata.creationTimestamp
```

This provides visibility into the image pull sequence.

---

## Why the Service Remained Available

The Deployment uses `RollingUpdate`.

The new revision could not become available.

Therefore Kubernetes preserved the previous healthy replicas.

```text
Stable ReplicaSet
   |
   +--> Ready Pod
   +--> Ready Pod

Broken ReplicaSet
   |
   +--> ImagePullBackOff Pod
```

Traffic continued to reach the Ready Pods from the stable revision.

---

## Availability Validation

During the failed rollout:

```bash
curl http://localhost:8888/api/info
```

continued to return the stable application response.

This confirmed:

```text
broken release
     |
     X
new Pod unavailable

stable release
     |
     v
traffic still served
```

---

## Rollout History

Deployment revisions were inspected:

```bash
kubectl rollout history deployment/booking-api -n booking-dev
```

ReplicaSets were inspected using:

```bash
kubectl get rs -n booking-dev \
  -o custom-columns='NAME:.metadata.name,REVISION:.metadata.annotations.deployment\.kubernetes\.io/revision,IMAGE:.spec.template.spec.containers[0].image,REPLICAS:.spec.replicas'
```

This helped identify which revision referenced the invalid image.

---

## Root Cause

The Deployment referenced a nonexistent image:

```text
kube-lab/booking-api:0.3.0-broken
```

Possible real-world causes of the same incident include:

```text
incorrect image tag
image not pushed to registry
wrong registry URL
registry authentication failure
missing imagePullSecret
network issue reaching registry
image deleted from registry
```

---

## Resolution

The Deployment was rolled back:

```bash
kubectl rollout undo deployment/booking-api -n booking-dev
```

The restored Deployment was monitored:

```bash
kubectl rollout status deployment/booking-api -n booking-dev
```

The healthy image became active again:

```text
kube-lab/booking-api:0.2.0
```

---

## Declarative State Warning

Because the Deployment was managed through:

```bash
kubectl apply
```

the imperative rollback generated a warning.

`rollout undo` changed the live Deployment state but did not update:

```text
kubectl.kubernetes.io/last-applied-configuration
```

Therefore the YAML source of truth also had to be corrected.

---

## Final Realignment

The local manifest was restored to:

```yaml
image: kube-lab/booking-api:0.2.0
```

Then reapplied:

```bash
kubectl apply -f k8s/base/booking-api.yml
```

Final state:

```text
Git manifest
      =
kubectl last-applied
      =
live Deployment
```

---

## Revision Observation

Rollback may reuse an older ReplicaSet template while assigning it a new current Deployment revision.

Therefore an older revision number may no longer represent the current Deployment state exactly as before.

Revision history should be interpreted together with:

```text
ReplicaSet
image tag
Pod template
```

---

## Troubleshooting Model

When a Pod reports `ImagePullBackOff`:

```text
Pod not starting
      |
      v
kubectl describe pod
      |
      v
Events
      |
      v
image pull failure?
      |
      +--> image exists?
      |
      +--> tag correct?
      |
      +--> registry reachable?
      |
      +--> credentials valid?
      |
      +--> imagePullSecret present?
```

---

## Useful Commands

```bash
kubectl get pods -n booking-dev

kubectl describe pod <pod> -n booking-dev

kubectl get events \
  -n booking-dev \
  --sort-by=.metadata.creationTimestamp

kubectl rollout history deployment/booking-api -n booking-dev

kubectl get rs -n booking-dev

kubectl rollout undo deployment/booking-api -n booking-dev

kubectl rollout status deployment/booking-api -n booking-dev
```

---

## Key Learnings

- `ErrImagePull` indicates an immediate image retrieval failure.
- `ImagePullBackOff` indicates repeated pull failures with retry backoff.
- RollingUpdate can protect availability during a broken release.
- Old Ready replicas can continue serving traffic.
- Image tags are part of deployment reliability.
- Rollback can rapidly restore a stable Deployment.
- Imperative rollback and declarative configuration must be reconciled afterwards.
- Registry access is part of application delivery reliability.
- Kubernetes events are critical when diagnosing image startup failures.

---

## Resolution Status

```text
✅ Broken release reproduced
✅ ErrImagePull observed
✅ ImagePullBackOff observed
✅ Stable replicas remained available
✅ Service availability preserved
✅ Root cause identified
✅ Rollback executed
✅ Stable image restored
✅ Declarative state realigned
```

**Status: RESOLVED**
