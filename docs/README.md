# Kubernetes Enterprise Lab — Documentation

This directory contains the technical documentation, implementation steps and troubleshooting exercises completed as part of the Kubernetes Enterprise Lab.

The lab progressively builds a production-like application platform using:

- Angular
- Spring Boot
- Docker
- Kubernetes
- Gateway API
- Ingress
- Envoy Gateway
- Kustomize
- GitOps / Argo CD
- OpenShift

The objective is not only to deploy workloads, but also to understand how they are operated, exposed, secured, scaled and troubleshooted in enterprise environments.

---

# Lab Progress

| ID        | Topic                             | Status       | Documentation                                                       |
| --------- | --------------------------------- | ------------ | ------------------------------------------------------------------- |
| PLAT-001  | Containerize frontend and backend | ✅ Completed | Application bootstrap                                               |
| PLAT-002  | Deploy Booking API                | ✅ Completed | [Booking API Deployment](./labs/PLAT-002-booking-api-deployment.md) |
| PLAT-003  | Deploy Angular Frontend           | ✅ Completed | [Frontend Deployment](./labs/PLAT-003-frontend-deployment.md)       |
| PLAT-004  | Gateway API & Envoy Routing       | ✅ Completed | [Gateway API Routing](./labs/PLAT-004-gateway-api-routing.md)       |
| PLAT-004B | Legacy Kubernetes Ingress         | ⏳ Next      | Coming next                                                         |
| PLAT-005  | ConfigMap & Secrets               | ⬜ Planned   | —                                                                   |
| PLAT-006  | Health Probes                     | ⬜ Planned   | —                                                                   |
| PLAT-007  | Resources & Limits                | ⬜ Planned   | —                                                                   |
| PLAT-008  | Rolling Updates & Rollbacks       | ⬜ Planned   | —                                                                   |
| PLAT-009  | Horizontal Pod Autoscaling        | ⬜ Planned   | —                                                                   |
| PLAT-010  | RBAC & Service Accounts           | ⬜ Planned   | —                                                                   |
| PLAT-011  | Network Policies                  | ⬜ Planned   | —                                                                   |
| PLAT-012  | Kustomize Environments            | ⬜ Planned   | —                                                                   |
| PLAT-013  | GitOps with Argo CD               | ⬜ Planned   | —                                                                   |
| PLAT-014  | OpenShift Migration               | ⬜ Planned   | —                                                                   |

---

# Current Architecture

```text
                         Client
                            |
                            v
                     Envoy Gateway
                            |
                         HTTPRoute
                       /            \
                      /              \
                     v                v
          booking-frontend          booking-api
              Service                Service
             ClusterIP              ClusterIP
                 |                     |
            +----+----+           +----+----+
            |         |           |         |
        Front Pod  Front Pod    API Pod   API Pod
            |                         |
          Nginx                  Spring Boot
            |
          Angular
```

Both application Services remain internal to the Kubernetes cluster.

External HTTP traffic is handled through the platform edge layer.

---

# Completed Labs

## PLAT-002 — Booking API Deployment

The backend was deployed using a Kubernetes `Deployment` with two replicas and exposed internally through a `ClusterIP` Service.

Key concepts:

- Namespace
- Deployment
- ReplicaSet
- Pods
- Services
- ClusterIP
- Labels and selectors
- Kubernetes DNS
- EndpointSlice
- Internal service discovery

➡️ [Read PLAT-002](./labs/PLAT-002-booking-api-deployment.md)

---

## PLAT-003 — Frontend Deployment

The Angular frontend was containerized with Nginx and deployed using two replicas.

A dedicated `ClusterIP` Service provides stable internal access to the frontend.

Key concepts:

- Frontend workload deployment
- Nginx runtime
- Service discovery
- Deployment / Service pattern
- Angular development proxy vs production runtime
- Internal cluster networking

➡️ [Read PLAT-003](./labs/PLAT-003-frontend-deployment.md)

---

## PLAT-004 — Gateway API Routing

A shared HTTP entry point was introduced using Gateway API and Envoy Gateway.

Routing rules:

```text
/       -> booking-frontend:8080
/api/*  -> booking-api:8080
```

Key concepts:

- Gateway API
- GatewayClass
- Gateway
- HTTPRoute
- Envoy Gateway
- Control plane vs data plane
- Path-based routing
- LoadBalancer
- Docker Desktop networking
- `kubectl port-forward`

➡️ [Read PLAT-004](./labs/PLAT-004-gateway-api-routing.md)

---

# Troubleshooting Journal

Dedicated incident scenarios are documented separately from the implementation labs.

The goal is to reproduce common Kubernetes production failures and document:

```text
Symptoms
   ↓
Investigation
   ↓
Root Cause
   ↓
Resolution
   ↓
Validation
   ↓
Prevention
```

Examples that will progressively be introduced:

| Incident | Scenario                                     |
| -------- | -------------------------------------------- |
| INC-001  | Service exposed incorrectly through NodePort |
| INC-002  | Service has no endpoints                     |
| INC-003  | CrashLoopBackOff                             |
| INC-004  | ImagePullBackOff                             |
| INC-005  | Readiness probe failure                      |
| INC-006  | Liveness probe failure                       |
| INC-007  | OOMKilled                                    |
| INC-008  | RBAC Forbidden                               |
| INC-009  | NetworkPolicy blocking traffic               |
| INC-010  | Misconfigured ConfigMap / Secret             |

Incident reports are stored in:

```text
docs/incidents/
```

---

# Documentation Pattern

Each platform lab follows the same structure:

```text
Objective
    ↓
Architecture
    ↓
Kubernetes Resources
    ↓
Implementation
    ↓
Validation
    ↓
Issue Encountered
    ↓
Resolution
    ↓
Troubleshooting Commands
    ↓
Key Learnings
    ↓
Result
```

This keeps every exercise reproducible and makes the evolution of the platform easy to follow.

---

# Learning Path

The lab progressively moves through several layers of Kubernetes platform engineering.

```text
Containers
    ↓
Pods / Deployments
    ↓
Services
    ↓
Service Discovery
    ↓
Gateway API / Ingress
    ↓
Configuration
    ↓
Health Checks
    ↓
Resource Management
    ↓
Resilience
    ↓
Security
    ↓
Autoscaling
    ↓
Environment Management
    ↓
GitOps
    ↓
OpenShift
```

The goal is to build transferable Kubernetes knowledge rather than knowledge tied to a single Kubernetes distribution.

---

# Next Lab

## PLAT-004B — Legacy Kubernetes Ingress

The same application routing currently implemented with Gateway API will be reproduced using the traditional Kubernetes `Ingress` model.

Target routing:

```text
/
    -> booking-frontend

/api
    -> booking-api
```

This exercise will allow a direct comparison between:

```text
IngressClass              GatewayClass
      |                         |
Ingress Controller          Gateway
      |                         |
   Ingress                  HTTPRoute
      |                         |
   Services                  Services
```

The objective is to understand both the model still commonly found in existing enterprise clusters and the newer Gateway API architecture.
