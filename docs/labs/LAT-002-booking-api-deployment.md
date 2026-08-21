# PLAT-002 — Deploy Booking API on Kubernetes

## Objective

Deploy the `booking-api` application into a dedicated Kubernetes development namespace.

The API must:

- Run with 2 replicas
- Be accessible from other workloads inside the cluster
- Be reachable through the DNS name `booking-api`
- Not be directly exposed outside the Kubernetes cluster

---

## Architecture

```text
                    Kubernetes Cluster
                           |
                    booking-dev
                           |
            +--------------+--------------+
            |                             |
            |      booking-api Service    |
            |         ClusterIP           |
            |          :8080              |
            |                             |
            +--------------+--------------+
                           |
                    app=booking-api
                           |
              +------------+------------+
              |                         |
              v                         v
      booking-api Pod           booking-api Pod
        10.1.1.11                 10.1.1.12
          :8080                     :8080
```

Internal workloads can access the API through:

```text
http://booking-api:8080
```

---

## Kubernetes Resources

The following Kubernetes resources were created:

```text
Namespace
└── booking-dev

Deployment
└── booking-api
    └── replicas: 2

Service
└── booking-api
    └── type: ClusterIP
```

---

## Deployment

The backend is deployed using a Kubernetes `Deployment`.

```yaml
apiVersion: apps/v1
kind: Deployment

metadata:
  name: booking-api
  labels:
    app: booking-api

spec:
  replicas: 2

  selector:
    matchLabels:
      app: booking-api

  template:
    metadata:
      labels:
        app: booking-api

    spec:
      containers:
        - name: booking-api
          image: kube-lab/booking-api:0.1.0

          ports:
            - containerPort: 8080
```

The Deployment ensures that Kubernetes maintains two running instances of the application.

---

## Internal Service

The backend must only be reachable from inside the Kubernetes cluster.

A `ClusterIP` Service is therefore used.

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

The Service selects every Pod containing the following label:

```yaml
app: booking-api
```

This label matches the Pods created by the `booking-api` Deployment.

---

## Validation

### Check Pods

```bash
kubectl get pods -n booking-dev
```

Result:

```text
NAME                           READY   STATUS    RESTARTS
booking-api-d9d8bc57d-499sn    1/1     Running   0
booking-api-d9d8bc57d-qwj8q    1/1     Running   0
```

Both replicas are running successfully.

---

### Check Pod IP addresses

```bash
kubectl get pods -n booking-dev -o wide
```

Result:

```text
NAME                           READY   STATUS    IP
booking-api-d9d8bc57d-499sn    1/1     Running   10.1.1.11
booking-api-d9d8bc57d-qwj8q    1/1     Running   10.1.1.12
```

Each Pod has its own internal cluster IP address.

---

### Check Service

```bash
kubectl get svc -n booking-dev
```

Expected result:

```text
NAME          TYPE        CLUSTER-IP       EXTERNAL-IP   PORT(S)
booking-api   ClusterIP   <cluster-ip>     <none>        8080/TCP
```

The API has no external IP and no NodePort.

---

## EndpointSlice Validation

Modern Kubernetes versions use `EndpointSlice` to maintain the backend endpoints associated with a Service.

```bash
kubectl get endpointslices \
  -n booking-dev \
  -l kubernetes.io/service-name=booking-api
```

The `booking-api` Service resolves to the two backend Pods:

```text
10.1.1.11:8080
10.1.1.12:8080
```

Conceptually:

```text
booking-api Service
        |
        +----> 10.1.1.11:8080
        |
        +----> 10.1.1.12:8080
```

This confirms that the Service selector correctly matches both backend replicas.

---

## Internal Connectivity Test

A temporary debug Pod was used to test the Service from inside the Kubernetes cluster.

```bash
kubectl run curl \
  --image=curlimages/curl \
  --rm -it \
  --restart=Never \
  -n booking-dev \
  -- curl http://booking-api:8080/api/info
```

The request successfully reaches the Spring Boot API using Kubernetes internal DNS.

Example response:

```json
{
  "application": "booking-api",
  "environment": "local",
  "instance": "booking-api-d9d8bc57d-499sn",
  "version": "1.0.0"
}
```

The hostname returned through the `instance` property identifies the Pod that handled the request.

With multiple replicas, successive requests may be handled by different Pods.

---

## Issue Encountered

The Service was initially configured using:

```yaml
type: NodePort
```

with:

```yaml
nodePort: 30007
```

The resulting Service looked like:

```text
NAME          TYPE       CLUSTER-IP       PORT(S)
booking-api   NodePort   10.100.106.143   8080:30007/TCP
```

This configuration worked technically, but it violated the platform requirement:

> The backend API must not be directly exposed outside the Kubernetes cluster.

A `NodePort` exposes the Service through a port on every Kubernetes node.

---

## Resolution

The Service was changed from:

```yaml
type: NodePort
```

to:

```yaml
type: ClusterIP
```

The explicit `nodePort` configuration was removed.

The final networking model is therefore:

```text
Outside cluster
      |
      X
      |
booking-api Service
   ClusterIP
      |
   +--+--+
   |     |
 Pod 1  Pod 2
```

The backend remains accessible to workloads inside Kubernetes while having no direct external exposure.

---

## Troubleshooting Commands

Useful commands used during the deployment:

```bash
kubectl get all -n booking-dev

kubectl get pods -n booking-dev

kubectl get pods -n booking-dev -o wide

kubectl get svc -n booking-dev

kubectl get endpointslices -n booking-dev

kubectl get endpointslices \
  -n booking-dev \
  -l kubernetes.io/service-name=booking-api

kubectl describe deployment booking-api -n booking-dev

kubectl describe service booking-api -n booking-dev
```

Internal DNS and connectivity test:

```bash
kubectl run curl \
  --image=curlimages/curl \
  --rm -it \
  --restart=Never \
  -n booking-dev \
  -- curl http://booking-api:8080/api/info
```

---

## Key Learnings

### Deployment

A Kubernetes `Deployment` manages the desired number of application replicas.

```text
Deployment
    |
ReplicaSet
    |
 +--+--+
 |     |
Pod   Pod
```

---

### Service

Pods are ephemeral and their IP addresses can change.

Applications should therefore not communicate directly using Pod IP addresses.

A Kubernetes `Service` provides a stable abstraction in front of the Pods.

```text
Application
     |
booking-api
     |
 Service
     |
 +---+---+
 |       |
Pod     Pod
```

---

### Service Discovery

Inside the same namespace, Kubernetes DNS allows workloads to reach the backend using:

```text
booking-api
```

instead of relying on Pod IP addresses.

Example:

```text
http://booking-api:8080/api/info
```

---

### ClusterIP vs NodePort

`ClusterIP`:

```text
Internal Kubernetes access only
```

`NodePort`:

```text
Cluster
   |
Node IP : NodePort
   |
External access possible
```

For an internal backend service, `ClusterIP` is the appropriate choice.

---

### Labels and Selectors

The Service discovers Pods through labels.

Deployment Pod label:

```yaml
labels:
  app: booking-api
```

Service selector:

```yaml
selector:
  app: booking-api
```

If these values do not match, the Service will have no backend endpoints.

---

### EndpointSlice

`EndpointSlice` represents the actual backend endpoints associated with a Kubernetes Service.

For this deployment:

```text
booking-api
    |
    +--> 10.1.1.11:8080
    |
    +--> 10.1.1.12:8080
```

Checking EndpointSlices is therefore an important troubleshooting step when a Service exists but traffic does not reach the expected Pods.

---

## Result

PLAT-002 completed successfully.

```text
✅ Dedicated booking-dev namespace
✅ booking-api Deployment
✅ 2 backend replicas
✅ Stable internal Kubernetes Service
✅ Internal DNS through booking-api
✅ ClusterIP-only exposure
✅ EndpointSlice correctly targeting both Pods
✅ API reachable from another Pod inside the cluster
```

The backend is now ready to be consumed by the frontend workload in the next stage of the lab.
