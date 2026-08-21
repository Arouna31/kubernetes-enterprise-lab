# INC-002 — Gateway Waiting for Controller

## Incident Summary

A Kubernetes Gateway was created for the Booking Platform using Gateway API.

The resource was accepted by the Kubernetes API server but remained in a pending state:

```text
Waiting for controller
```

The Gateway could therefore not expose or route application traffic.

---

## Environment

```text
Namespace: booking-dev
Gateway API: gateway.networking.k8s.io/v1
Gateway implementation: Envoy Gateway
Frontend Service: booking-frontend
Backend Service: booking-api
```

---

## Symptoms

The Gateway existed:

```bash
kubectl get gateway -n booking-dev
```

but inspection showed:

```bash
kubectl describe gateway booking-gateway -n booking-dev
```

Status:

```text
Conditions:

Type: Accepted
Status: Unknown
Reason: Pending
Message: Waiting for controller

Type: Programmed
Status: Unknown
Reason: Pending
Message: Waiting for controller
```

The Gateway was therefore neither accepted by an implementation nor programmed.

---

## Gateway Configuration

The Gateway referenced:

```yaml
gatewayClassName: booking-gateway-class
```

Example:

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway

metadata:
  name: booking-gateway
  namespace: booking-dev

spec:
  gatewayClassName: booking-gateway-class

  listeners:
    - name: http
      protocol: HTTP
      port: 8080

      allowedRoutes:
        namespaces:
          from: Same
```

---

## Investigation

The first diagnostic step was:

```bash
kubectl get gatewayclass
```

The required:

```text
booking-gateway-class
```

did not initially exist.

The Gateway therefore referenced a GatewayClass that no controller was managing.

Conceptually:

```text
Gateway
   |
gatewayClassName:
booking-gateway-class
   |
   X
No GatewayClass
   |
   X
No controller ownership
```

The Kubernetes API server was able to store the resource, but no controller could implement its desired state.

---

## Root Cause

The required `GatewayClass` was missing.

A Gateway API implementation requires the following relationship:

```text
Gateway
   |
   v
GatewayClass
   |
controllerName
   |
   v
Gateway Controller
```

Without a valid GatewayClass linked to an installed controller, the Gateway remains pending.

---

## Resolution

A GatewayClass was created for Envoy Gateway.

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass

metadata:
  name: booking-gateway-class

spec:
  controllerName: gateway.envoyproxy.io/gatewayclass-controller
```

`GatewayClass` is a cluster-scoped resource and therefore has no namespace.

The resource was applied using:

```bash
kubectl apply -f k8s/base/gateway-class.yml
```

---

## GatewayClass Validation

```bash
kubectl get gatewayclass
```

Result:

```text
NAME                    CONTROLLER                                      ACCEPTED
booking-gateway-class   gateway.envoyproxy.io/gatewayclass-controller   True
```

This confirms that Envoy Gateway recognized the class.

---

## Gateway Validation

After creation of the GatewayClass:

```bash
kubectl get gateway -n booking-dev
```

returned:

```text
NAME              CLASS                   ADDRESS         PROGRAMMED
booking-gateway   booking-gateway-class   192.168.59.11   True
```

The Gateway transitioned from:

```text
Pending
```

to:

```text
Programmed=True
```

---

## HTTPRoute Validation

The attached HTTPRoute was inspected using:

```bash
kubectl describe httproute booking-route -n booking-dev
```

Relevant conditions:

```text
Type: Accepted
Status: True

Type: ResolvedRefs
Status: True
```

This confirms that the route was successfully accepted and its backend references were resolved.

---

## Data Plane Provisioning

Envoy Gateway automatically provisioned an Envoy proxy for the Gateway.

```bash
kubectl get pods -n envoy-gateway-system
```

Observed resources:

```text
envoy-gateway-...
envoy-booking-dev-booking-gateway-...
```

Conceptually:

```text
CONTROL PLANE

Envoy Gateway Controller
         |
         | watches
         v
GatewayClass
Gateway
HTTPRoute
         |
         v
generates proxy configuration


DATA PLANE

Envoy Proxy
         |
         v
booking-frontend
booking-api
```

---

## Network Service Provisioning

Envoy Gateway also provisioned a Service:

```bash
kubectl get svc -n envoy-gateway-system
```

Example:

```text
NAME                                         TYPE           EXTERNAL-IP
envoy-booking-dev-booking-gateway-...        LoadBalancer   192.168.59.11
```

This Service represents the network edge for the Gateway.

The application Services remained internal:

```text
booking-frontend   ClusterIP
booking-api        ClusterIP
```

---

## Secondary Issue — LoadBalancer IP Not Reachable from macOS

Even after:

```text
GatewayClass Accepted=True
Gateway Programmed=True
HTTPRoute Accepted=True
HTTPRoute ResolvedRefs=True
```

the Gateway address could not initially be reached directly from the macOS host:

```bash
curl http://192.168.59.11:8080
```

Result:

```text
curl: (7) Failed to connect
```

This was not caused by Gateway API.

The `192.168.59.11` address belonged to the Docker Desktop Kubernetes networking environment and was not directly routable from the macOS host in this setup.

---

## Local Resolution

A port-forward was created toward the Envoy data-plane Service:

```bash
kubectl -n envoy-gateway-system port-forward \
  service/envoy-booking-dev-booking-gateway-317b7f22 \
  8888:8080
```

The platform then became available through:

```text
http://localhost:8888
```

---

## Final Validation

Browser access to:

```text
http://localhost:8888
```

successfully loaded the Angular frontend.

Requests to:

```text
/api/hotels
```

were correctly routed to Spring Boot.

The resulting traffic path:

```text
Browser
   |
localhost:8888
   |
kubectl port-forward
   |
Envoy Proxy
   |
HTTPRoute
   |
   +------------------+
   |                  |
   v                  v
/api/*                 /
   |                  |
booking-api       booking-frontend
   |                  |
API Pods          Frontend Pods
```

---

## Troubleshooting Commands

Inspect Gateway classes:

```bash
kubectl get gatewayclass
```

Inspect Gateway:

```bash
kubectl get gateway -n booking-dev

kubectl describe gateway booking-gateway -n booking-dev
```

Inspect routes:

```bash
kubectl get httproute -n booking-dev

kubectl describe httproute booking-route -n booking-dev
```

Inspect Envoy Gateway:

```bash
kubectl get pods -n envoy-gateway-system

kubectl get svc -n envoy-gateway-system
```

Inspect available Gateway API resources:

```bash
kubectl api-resources | grep -Ei "gateway|httproute"
```

---

## Troubleshooting Model

A useful troubleshooting sequence for Gateway API is:

```text
GatewayClass
   |
   └── Accepted=True?
             |
             v
Gateway
   |
   ├── Accepted=True?
   └── Programmed=True?
             |
             v
HTTPRoute
   |
   ├── Accepted=True?
   └── ResolvedRefs=True?
             |
             v
Controller / Data Plane
             |
             v
Services
             |
             v
Pods
```

Investigation should move layer by layer instead of immediately modifying application resources.

---

## Key Learnings

- Kubernetes resources often require controllers to implement their desired state.
- A `Gateway` alone does not provide traffic routing.
- A `GatewayClass` connects Gateway resources to a specific implementation.
- `controllerName` identifies the controller responsible for that GatewayClass.
- `Accepted=True` confirms controller ownership.
- `Programmed=True` confirms that the Gateway infrastructure has been configured.
- `ResolvedRefs=True` confirms valid route backend references.
- Envoy Gateway acts as the control plane while Envoy Proxy carries application traffic.
- Network reachability from the developer workstation is separate from Kubernetes resource health.
- A correctly programmed Gateway can still require local tunneling or port-forwarding in a development environment.

---

## Resolution Status

```text
✅ Missing GatewayClass identified
✅ Envoy Gateway controller associated
✅ GatewayClass Accepted=True
✅ Gateway Programmed=True
✅ HTTPRoute Accepted=True
✅ HTTPRoute ResolvedRefs=True
✅ Envoy data plane provisioned
✅ Local host access restored using port-forward
✅ Frontend and API routing validated
```

**Status: RESOLVED**
