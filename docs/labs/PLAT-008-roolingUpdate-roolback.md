# PLAT-008 — Rolling Update, Release Failure and Rollback

## Objective

Understand how Kubernetes manages application releases through:

- Deployment revisions
- ReplicaSets
- Rolling updates
- Failed releases
- Rollout history
- Rollback operations

The exercise simulates a normal release followed by a broken release.

---

## Release Scenario

The initial stable application version is:

```text
kube-lab/booking-api:0.1.0
```

The target release sequence is:

```text
v0.1.0
   |
   v
v0.2.0
   |
   v
broken v0.3.0
   |
   v
rollback
   |
   v
v0.2.0
```

---

## Target Architecture

```text
                     Deployment
                         |
                    RollingUpdate
                         |
               +---------+---------+
               |                   |
               v                   v
         Old ReplicaSet      New ReplicaSet
               |                   |
           Ready Pods           New Pods
```

During a successful rollout, traffic progressively moves from the old ReplicaSet to the new one.

---

## Preparing Version 0.2.0

For this lab, version `0.2.0` reuses the same application image content with a new tag.

```bash
docker tag \
  kube-lab/booking-api:0.1.0 \
  kube-lab/booking-api:0.2.0
```

Validation:

```bash
docker images | grep kube-lab/booking-api
```

Expected:

```text
kube-lab/booking-api   0.2.0
kube-lab/booking-api   0.1.0
```

---

## Deploying Version 0.2.0

The Deployment image was changed to:

```yaml
image: kube-lab/booking-api:0.2.0
```

The application configuration was also updated:

```yaml
APP_VERSION: 0.2.0
```

The resources were applied:

```bash
kubectl apply -f k8s/base/booking-api-config.yml
kubectl apply -f k8s/base/booking-api.yml
```

---

## Rollout Monitoring

The rollout was monitored using:

```bash
kubectl rollout status deployment/booking-api -n booking-dev
```

Pods were observed with:

```bash
kubectl get pods -n booking-dev -w
```

The expected transition is:

```text
old Pods v0.1.0
      |
      v
new Pods v0.2.0 created
      |
      v
readiness probes succeed
      |
      v
old Pods removed
```

---

## ReplicaSet Observation

ReplicaSets were inspected with:

```bash
kubectl get rs -n booking-dev
```

Conceptually:

```text
Deployment
   |
   +--> old ReplicaSet
   |       replicas: 0
   |
   +--> new ReplicaSet
           replicas: 2
```

Each meaningful Pod template change can create a new Deployment revision.

---

## Application Validation

The application was validated through the existing Gateway:

```bash
curl http://localhost:8888/api/info
```

Expected:

```json
{
  "application": "booking-api",
  "environment": "dev",
  "instance": "booking-api-...",
  "version": "0.2.0"
}
```

Version `0.2.0` was therefore considered stable.

---

# Broken Release Simulation

A deliberately invalid image was configured:

```yaml
image: kube-lab/booking-api:0.3.0-broken
```

This image does not exist.

The Deployment was reapplied:

```bash
kubectl apply -f k8s/base/booking-api.yml
```

---

## Expected Behaviour

```text
Deployment updated
      |
      v
new ReplicaSet created
      |
      v
new Pod starts
      |
      v
image pull attempted
      |
      v
failure
```

The new revision cannot become available.

---

## Observed Rollout Protection

The new Pod entered an image pull failure state while the previous healthy Pods remained available.

Conceptually:

```text
Old ReplicaSet
    |
    +--> Pod #1 Ready
    +--> Pod #2 Ready

New ReplicaSet
    |
    +--> Pod #3 ImagePullBackOff
```

Kubernetes did not immediately remove the healthy replicas from the previous revision.

---

## Service Availability

The API was tested while the new release was broken:

```bash
curl http://localhost:8888/api/info
```

The previous healthy version remained available.

This demonstrates:

```text
new release broken
       |
       X
cannot become Ready
       |
       v
previous release preserved
       |
       v
service remains available
```

---

## Rollout History

Deployment history was inspected using:

```bash
kubectl rollout history deployment/booking-api -n booking-dev
```

A specific revision can be inspected using:

```bash
kubectl rollout history deployment/booking-api \
  -n booking-dev \
  --revision=<revision>
```

ReplicaSets can also be inspected directly:

```bash
kubectl get rs -n booking-dev \
  -o custom-columns='NAME:.metadata.name,REVISION:.metadata.annotations.deployment\.kubernetes\.io/revision,IMAGE:.spec.template.spec.containers[0].image,REPLICAS:.spec.replicas'
```

---

## Rollback

The Deployment was rolled back using:

```bash
kubectl rollout undo deployment/booking-api -n booking-dev
```

The rollout was then monitored:

```bash
kubectl rollout status deployment/booking-api -n booking-dev
```

The active image was validated with:

```bash
kubectl get deployment booking-api \
  -n booking-dev \
  -o jsonpath='{.spec.template.spec.containers[0].image}'
```

The expected stable image was restored:

```text
kube-lab/booking-api:0.2.0
```

---

## Important Warning — Imperative Rollback vs Declarative Configuration

The Deployment was originally managed with:

```bash
kubectl apply
```

During rollback, Kubernetes displayed a warning indicating that `rollout undo` does not update:

```text
kubectl.kubernetes.io/last-applied-configuration
```

This creates a temporary difference between:

```text
live cluster state
```

and:

```text
last applied declarative state
```

Example:

```text
Manifest on disk
image = 0.3.0-broken

last-applied annotation
image = 0.3.0-broken

live Deployment after rollback
image = 0.2.0
```

---

## Declarative Realignment

After the emergency rollback, the manifest must also be corrected:

```yaml
image: kube-lab/booking-api:0.2.0
```

Then:

```bash
kubectl apply -f k8s/base/booking-api.yml
```

This restores consistency between:

```text
Git / local manifest
        =
last-applied state
        =
live cluster state
```

---

## Revision Behaviour After Rollback

A rollback does not simply move the Deployment revision number backwards.

Instead, Kubernetes restores the previous Pod template as the latest Deployment state.

Conceptually:

```text
Revision 7
image 0.2.0

Revision 8
image 0.3.0-broken

rollback
   |
   v

new current revision
using the old 0.2.0 template
```

Therefore revision numbers should not be treated as permanent application version identifiers.

ReplicaSets and image tags provide a clearer view of what actually ran.

---

## Rollback Strategy in Enterprise Environments

Emergency recovery may look like:

```text
production incident
      |
      v
kubectl rollout undo
      |
      v
service restored
```

But the declarative source of truth must then be updated:

```text
rollback cluster
      |
      v
fix Git manifest
      |
      v
apply / GitOps reconciliation
```

In GitOps environments, rollback is often performed by reverting the Git commit and letting the controller reconcile the cluster.

---

## Troubleshooting Commands

```bash
kubectl rollout status deployment/booking-api -n booking-dev

kubectl rollout history deployment/booking-api -n booking-dev

kubectl rollout history deployment/booking-api \
  -n booking-dev \
  --revision=<revision>

kubectl get rs -n booking-dev

kubectl get pods -n booking-dev -w

kubectl describe pod <pod> -n booking-dev

kubectl get events \
  -n booking-dev \
  --sort-by=.metadata.creationTimestamp

kubectl rollout undo deployment/booking-api -n booking-dev
```

---

## Key Learnings

- Deployments manage application revisions through ReplicaSets.
- RollingUpdate can preserve old healthy replicas while a new release starts.
- A failed release does not necessarily create downtime.
- `kubectl rollout history` helps inspect Deployment revisions.
- `kubectl rollout undo` is useful for emergency recovery.
- Rollbacks do not automatically update declarative configuration files.
- Git manifests must be realigned after imperative recovery.
- Deployment revisions are not equivalent to application versions.
- Rollback strategy becomes cleaner when Git is the source of truth.

---

## Result

PLAT-008 completed successfully.

```text
✅ Version 0.2.0 deployed
✅ New ReplicaSet observed
✅ Successful rolling update observed
✅ Broken release simulated
✅ Previous replicas preserved
✅ Application remained available
✅ Rollout history inspected
✅ Rollback executed
✅ Stable image restored
✅ Declarative state realigned
✅ Revision behaviour understood
```

The lab now demonstrates both normal release lifecycle management and recovery from a failed Kubernetes deployment.
