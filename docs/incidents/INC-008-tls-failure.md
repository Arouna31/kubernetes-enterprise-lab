# INC-008 — Metrics Server Kubelet TLS Certificate Failure

## Incident Summary

After installing Metrics Server, Kubernetes resource metrics were still unavailable.

Running:

```bash
kubectl top nodes
```

returned:

```text
error: Metrics API not available
```

Metrics Server logs showed an x509 certificate verification failure while attempting to scrape the kubelet.

---

## Environment

```text
Cluster: Docker Desktop Kubernetes
Kubernetes: 1.34
Metrics Server: 0.9.x
```

---

## Symptoms

The Metrics Server Pod was deployed, but:

```bash
kubectl top nodes
kubectl top pods
```

did not return metrics.

The APIService was not fully functional.

---

## Investigation

Metrics Server logs were inspected:

```bash
kubectl logs \
  -n kube-system \
  deployment/metrics-server
```

The logs contained a certificate validation error similar to:

```text
failed to verify certificate
x509
```

The failure occurred during communication between Metrics Server and the kubelet.

---

## Architecture

Metrics collection requires:

```text
Metrics Server
      |
      | HTTPS
      v
Kubelet
```

Metrics Server normally verifies the TLS certificate presented by the kubelet.

In this local Docker Desktop environment, that certificate could not be validated successfully.

---

## Root Cause

Metrics Server rejected the kubelet certificate because its certificate chain could not be successfully verified in the local development environment.

As a result:

```text
Metrics Server
      |
      X
Kubelet metrics unavailable
      |
      v
metrics.k8s.io unavailable
      |
      v
kubectl top fails
      |
      v
HPA cannot retrieve CPU metrics
```

---

## Resolution

For the local lab only, Metrics Server was configured with:

```text
--kubelet-insecure-tls
```

The Deployment was patched:

```bash
kubectl patch deployment metrics-server \
  -n kube-system \
  --type='json' \
  -p='[
    {
      "op":"add",
      "path":"/spec/template/spec/containers/0/args/-",
      "value":"--kubelet-insecure-tls"
    }
  ]'
```

---

## Rollout Validation

The Metrics Server rollout was monitored:

```bash
kubectl rollout status \
  deployment/metrics-server \
  -n kube-system
```

The APIService was inspected:

```bash
kubectl get apiservice v1beta1.metrics.k8s.io
```

Expected state:

```text
AVAILABLE
True
```

---

## Metrics Validation

After the change:

```bash
kubectl top nodes
```

and:

```bash
kubectl top pods -n booking-dev
```

returned CPU and memory metrics successfully.

---

## Security Consideration

The flag:

```text
--kubelet-insecure-tls
```

does not disable HTTPS.

The communication remains encrypted, but Metrics Server no longer validates the kubelet certificate.

Conceptually:

```text
HTTPS encryption
      ✅

certificate validation
      ❌
```

This is acceptable for this isolated local lab but should not be copied blindly into a production environment.

---

## Production Approach

A production platform should use properly trusted kubelet certificates.

Conceptually:

```text
trusted CA
    |
    v
kubelet certificate
    |
    v
Metrics Server validates certificate
```

Certificate validation should remain enabled whenever possible.

---

## Troubleshooting Model

When:

```text
kubectl top
```

returns:

```text
Metrics API not available
```

investigate:

```text
Metrics Server installed?
        |
        v
Pod Running?
        |
        v
APIService Available?
        |
        v
Metrics Server logs
        |
        +--> kubelet connectivity
        +--> TLS certificates
        +--> authorization
        +--> network
```

---

## Useful Commands

```bash
kubectl get pods -n kube-system | grep metrics-server

kubectl get apiservice v1beta1.metrics.k8s.io

kubectl describe apiservice v1beta1.metrics.k8s.io

kubectl logs \
  -n kube-system \
  deployment/metrics-server

kubectl top nodes

kubectl top pods -n booking-dev
```

---

## Key Learnings

- Installing Metrics Server does not guarantee metrics immediately work.
- `kubectl top` depends on the Metrics API.
- Metrics Server retrieves metrics from kubelets.
- TLS certificate failures can prevent kubelet scraping.
- Logs are essential when the Metrics API remains unavailable.
- `--kubelet-insecure-tls` is suitable only for controlled development environments.
- Production environments should use trusted kubelet certificates.
- HPA availability depends on a healthy metrics pipeline.

---

## Resolution Status

```text
✅ Metrics Server deployed
✅ Certificate error identified
✅ Root cause understood
✅ Local TLS workaround applied
✅ APIService became available
✅ kubectl top operational
✅ HPA metrics available
```

**Status: RESOLVED**
