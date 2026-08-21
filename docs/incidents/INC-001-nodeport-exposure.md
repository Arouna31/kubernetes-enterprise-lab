# INC-001 — Backend API Exposed Through NodePort

## Incident Summary

During the initial deployment of `booking-api`, the Kubernetes Service was configured as a `NodePort`.

The application was functional, but the Service configuration did not comply with the platform requirement:

> The backend API must remain accessible only from inside the Kubernetes cluster.

---

## Environment

```text
Namespace: booking-dev
Application: booking-api
Replicas: 2
Container port: 8080
Kubernetes: Docker Desktop
```

---

## Symptoms

The Service was created successfully:

```bash
kubectl get svc -n booking-dev
```

Observed result:

```text
NAME          TYPE       CLUSTER-IP       EXTERNAL-IP   PORT(S)
booking-api   NodePort   10.100.106.143   <none>        8080:30007/TCP
```

The Service exposed:

```text
Service port: 8080
NodePort:     30007
```

The backend Pods themselves were healthy:

```text
booking-api-d9d8bc57d-499sn   1/1   Running
booking-api-d9d8bc57d-qwj8q   1/1   Running
```

---

## Initial Configuration

The Service was initially declared as:

```yaml
apiVersion: v1
kind: Service

metadata:
  name: booking-api

spec:
  type: NodePort

  selector:
    app: booking-api

  ports:
    - protocol: TCP
      port: 8080
      targetPort: 8080
      nodePort: 30007
```

---

## Investigation

The platform requirement was:

```text
Other Kubernetes workloads
        |
        v
booking-api
```

The backend did not require direct access from outside the cluster.

However, `NodePort` changes the network model to:

```text
External client
      |
Node IP:30007
      |
      v
booking-api Service
      |
  +---+---+
  |       |
Pod #1  Pod #2
```

The Service therefore exposed more network surface than required.

---

## Root Cause

The wrong Kubernetes Service type was selected.

`NodePort` was used when the actual requirement was internal service-to-service communication.

For this use case, `ClusterIP` is the appropriate Service type.

---

## Resolution

The Service was changed to:

```yaml
apiVersion: v1
kind: Service

metadata:
  name: booking-api

spec:
  type: ClusterIP

  selector:
    app: booking-api

  ports:
    - protocol: TCP
      port: 8080
      targetPort: 8080
```

The explicit:

```yaml
nodePort: 30007
```

configuration was removed.

---

## Validation

The corrected Service was verified using:

```bash
kubectl get svc -n booking-dev
```

Result:

```text
NAME          TYPE        CLUSTER-IP       EXTERNAL-IP   PORT(S)
booking-api   ClusterIP   10.100.106.143   <none>        8080/TCP
```

No external port is exposed.

---

## Internal Connectivity Validation

A temporary debug Pod was used to test access from inside the cluster:

```bash
kubectl run curl \
  --image=curlimages/curl \
  --rm -it \
  --restart=Never \
  -n booking-dev \
  -- curl http://booking-api:8080/api/info
```

The API responded successfully.

Example:

```json
{
  "application": "booking-api",
  "environment": "local",
  "instance": "booking-api-d9d8bc57d-499sn",
  "version": "1.0.0"
}
```

---

## Endpoint Validation

The Service was also verified against its backend endpoints:

```bash
kubectl get endpointslices \
  -n booking-dev \
  -l kubernetes.io/service-name=booking-api
```

The Service correctly resolved to both backend Pods:

```text
10.1.1.11:8080
10.1.1.12:8080
```

---

## Final Architecture

```text
Outside Cluster
      |
      X
      |
      |
booking-api
ClusterIP Service
      |
   +--+--+
   |     |
   v     v
 Pod 1  Pod 2
```

Application workloads inside the cluster can reach:

```text
http://booking-api:8080
```

while the backend remains unavailable through a direct node port.

---

## Prevention

Before selecting a Kubernetes Service type, identify the required network scope.

```text
Internal only
    |
    v
ClusterIP
```

```text
Direct node-level exposure
    |
    v
NodePort
```

```text
External infrastructure load balancer
    |
    v
LoadBalancer
```

The smallest required exposure should generally be preferred.

---

## Key Learnings

- A technically functional configuration can still violate architecture or security requirements.
- `ClusterIP` is the standard choice for internal service-to-service communication.
- `NodePort` exposes a Service through a port on every Kubernetes node.
- Service type must be selected according to network scope.
- Backend APIs should not be externally exposed unless the architecture explicitly requires it.
- Kubernetes DNS allows internal workloads to use stable Service names instead of Pod IP addresses.

---

## Resolution Status

```text
✅ Root cause identified
✅ NodePort removed
✅ Service migrated to ClusterIP
✅ Backend remains internally reachable
✅ No direct external exposure
✅ Both backend replicas remain available
```

**Status: RESOLVED**
