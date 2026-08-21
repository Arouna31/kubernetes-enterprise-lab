# INC-003 — Ingress Created in Wrong Namespace

## Incident Summary

During the legacy Ingress exercise, Traefik repeatedly logged:

```text
Cannot create service
service not found
```

The application Services existed and were healthy, but Traefik could not resolve the backend referenced by one of the Ingress resources.

---

## Environment

```text
Ingress Controller: Traefik
Application namespace: booking-dev
Frontend Service: booking-frontend
Backend Service: booking-api
Ingress: booking-ingress
```

---

## Symptoms

Traefik logs showed repeated errors:

```text
Cannot create service
error="service not found"
ingress=booking-ingress
namespace=default
serviceName=booking-frontend
```

At the same time, the expected Ingress existed in:

```text
booking-dev
```

and correctly referenced the application Services.

---

## Initial Investigation

The expected Ingress was checked:

```bash
kubectl get ingress -n booking-dev
```

Result:

```text
NAME              CLASS     HOSTS           ADDRESS
booking-ingress   traefik   booking.local   192.168.59.12
```

Detailed inspection:

```bash
kubectl describe ingress booking-ingress -n booking-dev
```

showed valid backends:

```text
/api -> booking-api:8080
/    -> booking-frontend:8080
```

with healthy Pod endpoints.

---

## Log Analysis

Traefik logs contained two different namespace references.

Valid resource:

```text
ingress=booking-ingress
namespace=booking-dev
```

Failing resource:

```text
ingress=booking-ingress
namespace=default
```

This indicated that two resources with the same name existed in different namespaces.

---

## Root Cause

The Ingress had initially been created before the manifest explicitly declared:

```yaml
namespace: booking-dev
```

The first resource was therefore created in:

```text
default
```

After the manifest was corrected and applied again, Kubernetes created another resource:

```text
booking-dev/booking-ingress
```

Kubernetes does not move resources between namespaces.

These are two different objects:

```text
default/booking-ingress

booking-dev/booking-ingress
```

---

## Why the Backend Was Not Found

The stale Ingress in `default` referenced:

```text
booking-frontend
```

but the Service existed in:

```text
booking-dev
```

Conceptually:

```text
default namespace

booking-ingress
      |
      | backend:
      | booking-frontend
      v
      X
Service not found
```

while:

```text
booking-dev namespace

booking-ingress
      |
      v
booking-frontend
      |
      ✅
```

Traditional Kubernetes Ingress backend Service references are namespace-local.

---

## Confirmation

All Ingress resources were listed:

```bash
kubectl get ingress -A
```

Expected problematic state:

```text
NAMESPACE     NAME
default       booking-ingress
booking-dev   booking-ingress
```

This confirmed the duplicate resource.

---

## Resolution

The obsolete Ingress was deleted from the `default` namespace:

```bash
kubectl delete ingress booking-ingress -n default
```

Validation:

```bash
kubectl get ingress -A
```

Final state:

```text
NAMESPACE     NAME
booking-dev   booking-ingress
```

Only the correct resource remained.

---

## Log Validation

Recent Traefik logs were checked:

```bash
kubectl logs \
  -n traefik-system \
  deployment/traefik \
  --since=2m
```

The repeated:

```text
service not found
namespace=default
```

errors stopped after the stale Ingress was removed.

---

## Final Architecture

```text
booking-dev
   |
   ├── booking-ingress
   |
   ├── booking-frontend Service
   |
   └── booking-api Service
```

All related routing resources now live in the same application namespace.

---

## Troubleshooting Commands

List all Ingress resources:

```bash
kubectl get ingress -A
```

Inspect namespace-specific Ingress:

```bash
kubectl describe ingress booking-ingress -n booking-dev
```

Inspect Services:

```bash
kubectl get svc -n booking-dev
```

Inspect controller logs:

```bash
kubectl logs \
  -n traefik-system \
  deployment/traefik
```

Check recent logs only:

```bash
kubectl logs \
  -n traefik-system \
  deployment/traefik \
  --since=5m
```

---

## Troubleshooting Model

When an Ingress Controller reports:

```text
service not found
```

check:

```text
Ingress exists?
      |
      v
Which namespace?
      |
      v
Service exists?
      |
      v
Same namespace?
      |
      v
Service name correct?
      |
      v
Service has endpoints?
```

This avoids immediately modifying routing rules when the real issue is resource scope.

---

## Key Learnings

- Kubernetes resource identity includes namespace.
- Two resources can have the same name in different namespaces.
- Updating a manifest namespace does not migrate the previous resource.
- Stale resources can continue to be watched by controllers.
- Controller logs often reveal the exact namespace of a failing resource.
- Traditional Ingress Service references are namespace-local.
- `kubectl get ... -A` is useful when a resource appears to behave inconsistently.
- Namespace mismatches are a common source of service discovery failures.

---

## Resolution Status

```text
✅ Duplicate Ingress identified
✅ Wrong namespace confirmed
✅ Stale default namespace resource removed
✅ Traefik errors stopped
✅ Correct Ingress retained in booking-dev
✅ Frontend routing validated
✅ Backend routing validated
```

**Status: RESOLVED**
