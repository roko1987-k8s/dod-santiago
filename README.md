# Zero Trust con Microservicios Java

Laboratorio práctico para demostrar conceptos de **Zero Trust** aplicados a microservicios sobre Kubernetes utilizando:

- Kubernetes
- KIND
- Java / Spring Boot
- Istio
- Envoy
- mTLS
- SPIFFE
- AuthorizationPolicy
- Kubernetes RBAC
- Argo CD / GitOps

---

# 1. Crear el cluster

```bash
kind create cluster --name dod-santiago --config cluster.yaml
```

Verificar el contexto:

```bash
kubectl config current-context
```

Debe mostrar:

```text
kind-dod-santiago
```

Verificar los nodos:

```bash
kubectl get nodes
```

---

# 2. Argo CD

## Obtener password inicial

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d
```

Usuario:

```text
admin
```

## Port-forward

```bash
kubectl -n argocd port-forward svc/argocd-server 8080:443
```

Acceder:

```text
https://localhost:8080
```

---

# 3. Microservicios

El laboratorio contiene tres microservicios desarrollados en **Java / Spring Boot**:

```text
micro-a
micro-b
micro-c
```

Comunicación esperada:

```text
micro-a ──────► micro-b
   │
   └────────X──► micro-c

micro-b ──────► micro-c

micro-c ──────X──► micro-a
micro-c ──────X──► micro-b
micro-b ──────X──► micro-a
```

---

# 4. Validar comunicación entre microservicios

```bash
for pair in "micro-a micro-b" "micro-b micro-c" "micro-c micro-a"; do
  origin=$(echo $pair | awk '{print $1}')
  target=$(echo $pair | awk '{print $2}')

  if output=$(kubectl exec -n demo svc/$origin -- curl -fsS "http://$target:8443/" 2>/dev/null); then
    echo "$output"
  else
    echo "$origin → $target BLOQUEADO"
  fi
done
```

---

# 5. Utilizar Istio

Etiquetar el namespace para habilitar la inyección automática del sidecar:

```bash
kubectl label namespace demo istio-injection=enabled --overwrite
```

Reiniciar los deployments para que los pods reciban el sidecar:

```bash
kubectl rollout restart deployment -n demo
```

Verificar:

```bash
kubectl get pods -n demo
```

Los pods deben mostrar `2/2`, es decir:

```text
Aplicación + Envoy sidecar
```

---

# 6. Comprobar mTLS

## 6.1 Certificado e identidad SPIFFE

```bash
kubectl exec -n demo "$(kubectl get pods -n demo --no-headers | grep '^micro-a' | awk '{print $1}')" -c istio-proxy -- \
curl -s http://127.0.0.1:15000/certs
```

Buscar una identidad similar a:

```text
spiffe://cluster.local/ns/demo/sa/micro-a
```

Esto permite comprobar que el workload tiene una identidad SPIFFE asociada a su ServiceAccount.

## 6.2 Comprobar el upstream hacia micro-b

```bash
kubectl exec -n demo "$(kubectl get pods -n demo --no-headers | grep '^micro-a' | awk '{print $1}')" -c istio-proxy -- \
curl -s http://127.0.0.1:15000/clusters | grep -A20 -B5 'micro-b'
```

Podemos encontrar:

```text
outbound|8443||micro-b.demo.svc.cluster.local
```

Esto indica que Envoy tiene un cluster outbound asociado al Service `micro-b`.

## 6.3 Comprobar TLS en el upstream

```bash
kubectl exec -n demo "$(kubectl get pods -n demo --no-headers | grep '^micro-a' | awk '{print $1}')" -c istio-proxy -- \
curl -s http://127.0.0.1:15000/config_dump \
| grep -A80 'outbound|8443||micro-b.demo.svc.cluster.local' \
| grep -E 'transport_socket|UpstreamTlsContext'
```

Esperamos encontrar:

```text
transport_socket
envoy.transport_sockets.tls
UpstreamTlsContext
```

Esto indica que el Envoy de `micro-a` tiene configurado TLS para el upstream hacia `micro-b`.

## 6.4 Obtener el Envoy config dump completo

```bash
kubectl exec -n demo "$(kubectl get pods -n demo --no-headers | grep '^micro-a' | awk '{print $1}')" -c istio-proxy -- \
curl -s http://127.0.0.1:15000/config_dump > /tmp/envoy-config.json
```

El archivo se guarda localmente como:

```text
/tmp/envoy-config.json
```

---

# 7. PeerAuthentication

Para aplicar enforcement de mTLS:

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: demo
spec:
  mtls:
    mode: STRICT
```

Aplicar:

```bash
kubectl apply -f peerauthentication.yaml
```

Verificar:

```bash
kubectl get peerauthentication -n demo
```

`STRICT` significa que los workloads deben utilizar mTLS.

Conceptualmente:

```text
PERMISSIVE

mTLS       ✓
plaintext  ✓

STRICT

mTLS       ✓
plaintext  ✗
```

Istio puede utilizar **Auto mTLS** incluso sin configurar explícitamente `STRICT`. `STRICT` agrega el enforcement para impedir tráfico plaintext hacia los workloads protegidos.

---

# 8. AuthorizationPolicy

Ejemplo de política que permite que `micro-a` acceda a `micro-b`:

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: allow-a-to-b
  namespace: demo
spec:
  selector:
    matchLabels:
      app: micro-b
  action: ALLOW
  rules:
  - from:
    - source:
        principals:
        - cluster.local/ns/demo/sa/micro-a
```

Esta política permite:

```text
micro-a → micro-b
```

utilizando la identidad:

```text
cluster.local/ns/demo/sa/micro-a
```

Una política equivalente puede permitir:

```text
micro-b → micro-c
```

---

# 9. Kubernetes RBAC

Validar los permisos del usuario `developer`:

```bash
kubectl auth can-i get pods --as=developer -n demo
kubectl auth can-i delete pods --as=developer -n demo
kubectl auth can-i update deployments --as=developer -n demo
```

Validar los permisos del usuario `admin`:

```bash
kubectl auth can-i update deployments --as=admin -n demo
```

Ejemplo esperado:

```text
developer → get pods       yes
developer → delete pods    yes
developer → update deploy  no

admin     → update deploy  yes
```

RBAC permite aplicar el principio de **least privilege**.

---

# 10. Modelo Zero Trust

```text
                    ZERO TRUST
                         │
        ┌────────────────┼────────────────┐
        │                │                │
       RBAC           Istio            GitOps
        │                │                │
   Usuarios/K8s       mTLS           Argo CD
   permisos           SPIFFE         Estado deseado
                         │
                         │
                AuthorizationPolicy
                         │
                         ▼
                   Microservicios
```

Cada capa responde una pregunta diferente:

```text
RBAC
¿Qué puede hacer el usuario?

ServiceAccount / SPIFFE
¿Quién es este workload?

mTLS
¿La comunicación está autenticada y cifrada?

AuthorizationPolicy
¿Este workload puede comunicarse con aquel?

GitOps
¿Cuál es el estado deseado y quién puede modificarlo?
```

Principio:

> **Never Trust, Always Verify**

---

# 11. Generar tráfico

Para generar múltiples requests:

```bash
for i in {1..10}; do
  echo "=== Request $i ==="
  kubectl exec -n demo svc/micro-a -- \
    curl -s http://micro-a:8443/
done
```

Esto permite generar tráfico para observarlo posteriormente desde Envoy y las herramientas de observabilidad.

---

# 12. Flujo completo

```text
                    Usuario
                       │
                       ▼
                      RBAC
                       │
                       ▼
                  Kubernetes
                       │
                       ▼
                  Microservice
                       │
                       ▼
                ServiceAccount
                       │
                       ▼
                SPIFFE Identity
                       │
                       ▼
                     Envoy
                       │
                       ▼
                      mTLS
                       │
                       ▼
              AuthorizationPolicy
                       │
                       ▼
                Envoy destino
                       │
                       ▼
                Microservicio
```

El objetivo es aplicar Zero Trust a nivel de usuarios, workloads y comunicaciones entre microservicios.
