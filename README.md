# Zero Trust con Kubernetes, Istio y Java Microservices

Laboratorio práctico para demostrar **Zero Trust**, identidad de workloads, **mTLS**, autorización entre microservicios, RBAC, GitOps con Argo CD, observabilidad con Prometheus/Kiali y traffic management con Istio.

## Arquitectura

```text
                         ┌─────────────────────┐
                         │       Argo CD        │
                         │       GitOps         │
                         └──────────┬──────────┘
                                    │
                                    ▼
                              Git Repository
                                    │
                                    ▼
┌───────────────────────────────────────────────────────────────────────┐
│                         Kubernetes / KIND                             │
│                                                                       │
│  Namespace: demo                                                      │
│                                                                       │
│   ┌────────────┐       mTLS        ┌────────────┐       mTLS          │
│   │  micro-a   │──────────────────►│  micro-b   │──────────────────► │
│   │  Java      │                   │  Java      │                    │
│   │  Envoy     │                   │  Envoy     │                    │
│   └────────────┘                   └────────────┘                    │
│          │                                                               │
│          │                                                               │
│          └───────────────► micro-c / Java / Envoy                      │
│                                                                       │
│  Identity: ServiceAccount + SPIFFE                                     │
│  Encryption/Auth: Istio mTLS                                           │
│  Authorization: AuthorizationPolicy                                    │
│  Traffic: VirtualService + DestinationRule                            │
│                                                                       │
│  Namespace: istio-system                                               │
│  ┌──────────┐  ┌────────────┐  ┌──────────────┐                       │
│  │  Istiod  │  │ Prometheus │  │    Kiali     │                       │
│  └──────────┘  └────────────┘  └──────────────┘                       │
└───────────────────────────────────────────────────────────────────────┘
```

## Componentes

- Kubernetes local con KIND
- Java 21 + Spring Boot
- Istio + Envoy sidecars
- Istio mTLS
- SPIFFE workload identity
- AuthorizationPolicy
- PeerAuthentication
- VirtualService
- DestinationRule
- Canary deployment
- Connection pools
- Outlier detection
- Kubernetes RBAC
- Argo CD
- Prometheus
- Kiali

---

# 1. Requisitos

macOS/Linux:

```bash
kubectl version --client
docker version
kind version
helm version
java -version
mvn -version
```

Opcional:

```bash
argocd version --client
```

---

# 2. Crear el cluster KIND

Cluster utilizado por el laboratorio:

```bash
kind create cluster --name dod-santiago --config cluster.yaml
```

Verificar:

```bash
kubectl cluster-info
kubectl get nodes
kubectl config current-context
```

Contexto esperado:

```text
kind-dod-santiago
```

---

# 3. Namespace de la aplicación

```bash
kubectl create namespace demo
```

---

# 4. Instalar Argo CD

Argo CD será utilizado como herramienta GitOps y como fuente de reconciliación del estado deseado.

Instalación:

```bash
kubectl create namespace argocd

kubectl apply -n argocd \
  --server-side \
  --force-conflicts \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

Verificar:

```bash
kubectl get pods -n argocd
kubectl get svc -n argocd
```

Esperar a que los componentes estén `Running`.

## Obtener credencial inicial

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d
```

Usuario:

```text
admin
```

## Acceder a la UI

```bash
kubectl -n argocd port-forward svc/argocd-server 8080:443
```

Abrir:

```text
https://localhost:8080
```

Usar:

```text
Username: admin
Password: <password-obtenido-del-secret>
```

## Instalar CLI de Argo CD en macOS

```bash
brew install argocd
```

Login:

```bash
argocd login localhost:8080 --username admin --insecure
```

> Para un laboratorio local se puede usar el certificado self-signed. En producción se debe utilizar un certificado válido y una estrategia de autenticación adecuada.

---

# 5. Instalar Istio con Helm

Este laboratorio utiliza **sidecar mode**, no ambient mode.

Agregar repositorio:

```bash
helm repo add istio https://istio-release.storage.googleapis.com/charts
helm repo update
```

Instalar las CRDs/base:

```bash
helm install istio-base istio/base \
  -n istio-system \
  --create-namespace \
  --set defaultRevision=default \
  --wait
```

Instalar Istiod:

```bash
helm install istiod istio/istiod \
  -n istio-system \
  --wait
```

Verificar:

```bash
kubectl get pods -n istio-system
kubectl get crd | grep istio
```

Verificar Helm:

```bash
helm list -n istio-system
```

---

# 6. Habilitar sidecar injection

Etiquetar el namespace:

```bash
kubectl label namespace demo istio-injection=enabled --overwrite
```

Reiniciar deployments existentes:

```bash
kubectl rollout restart deployment -n demo
```

Verificar:

```bash
kubectl get pods -n demo
```

Los pods de los microservicios deben mostrar:

```text
2/2
```

Esto significa:

```text
1/1 aplicación
1/1 istio-proxy / Envoy
```

---

# 7. Microservicios Java

Microservicios:

```text
micro-a
micro-b
micro-c
```

Tecnología:

```text
Java 21
Spring Boot
```

Imágenes:

```text
roko1987/micro-a:java
roko1987/micro-b:java
roko1987/micro-c:java
```

Puertos:

```text
8443
```

Importante:

> El puerto 8443 no significa automáticamente HTTPS. En este laboratorio la aplicación Java expone HTTP sobre el puerto 8443 y es Envoy quien gestiona el mTLS entre workloads.

Endpoints:

```text
micro-a: /
micro-a: /health
micro-a: /call-b

micro-b: /
micro-b: /health
micro-b: /call-c

micro-c: /
micro-c: /health
```

---

# 8. Compilar los microservicios

```bash
for micro in micro-a micro-b micro-c; do
  echo "=== Compilando $micro ==="
  (cd "app/$micro" && mvn clean package -DskipTests)
done
```

Construir imágenes:

```bash
docker build -t roko1987/micro-a:java app/micro-a
docker build -t roko1987/micro-b:java app/micro-b
docker build -t roko1987/micro-c:java app/micro-c
```

Push:

```bash
docker push roko1987/micro-a:java
docker push roko1987/micro-b:java
docker push roko1987/micro-c:java
```

---

# 9. Versionado para Canary

La aplicación Java obtiene la versión desde:

```text
VERSION
```

Ejemplo:

```yaml
env:
  - name: MICRO_B_URL
    value: http://micro-b:8443
  - name: VERSION
    value: "v1"
```

Para v2:

```yaml
env:
  - name: MICRO_B_URL
    value: http://micro-b:8443
  - name: VERSION
    value: "v2"
```

La respuesta de `/` muestra:

```json
{
  "service": "micro-a",
  "language": "Java",
  "framework": "Spring Boot",
  "version": "v1"
}
```

o:

```json
{
  "service": "micro-a",
  "language": "Java",
  "framework": "Spring Boot",
  "version": "v2"
}
```

---

# 10. ServiceAccounts e identidad

Cada microservicio tiene su propio ServiceAccount:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: micro-a
  namespace: demo
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: micro-b
  namespace: demo
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: micro-c
  namespace: demo
```

Los Deployments utilizan:

```yaml
spec:
  template:
    spec:
      serviceAccountName: micro-a
```

y equivalentemente para `micro-b` y `micro-c`.

La identidad de workload observada por Istio es, por ejemplo:

```text
spiffe://cluster.local/ns/demo/sa/micro-a
```

Conceptualmente:

```text
ServiceAccount
      ↓
Workload Identity
      ↓
SPIFFE identity
      ↓
mTLS
      ↓
AuthorizationPolicy
```

Frase clave:

> **ServiceAccount identifica al workload, mTLS autentica y protege la comunicación, y AuthorizationPolicy determina qué puede hacer ese workload.**

---

# 11. Istio mTLS

Aplicar `PeerAuthentication` en modo STRICT:

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
kubectl apply -f peer-authentication.yaml
```

Verificar:

```bash
kubectl get peerauthentication -n demo
```

## Modos

### PERMISSIVE

Acepta:

```text
mTLS
+
plaintext
```

### STRICT

Exige:

```text
mTLS
```

### DISABLE

Deshabilita mTLS para el workload.

Importante:

> La ausencia de `PeerAuthentication STRICT` no significa necesariamente que Istio no utilice mTLS. Auto mTLS puede hacer que Envoy utilice mTLS cuando ambos workloads tienen sidecars. `STRICT` añade la garantía de enforcement en el receptor.

---

# 12. Verificar identidad/certificado de Envoy

Obtener el certificado del sidecar:

```bash
kubectl exec -n demo \
  "$(kubectl get pods -n demo --no-headers | grep '^micro-a' | awk '{print $1}')" \
  -c istio-proxy -- \
  curl -s http://127.0.0.1:15000/certs
```

Buscar:

```text
spiffe://cluster.local/ns/demo/sa/micro-a
```

Esto demuestra que Envoy posee un certificado de workload gestionado por Istio y asociado a una identidad SPIFFE.

---

# 13. Ver configuración TLS de Envoy

Inspeccionar el upstream hacia `micro-b`:

```bash
kubectl exec -n demo \
  "$(kubectl get pods -n demo --no-headers | grep '^micro-a' | awk '{print $1}')" \
  -c istio-proxy -- \
  curl -s http://127.0.0.1:15000/config_dump \
  | grep -A80 'outbound|8443||micro-b.demo.svc.cluster.local' \
  | grep -E 'transport_socket|UpstreamTlsContext'
```

Esperar algo similar a:

```text
"transport_socket": {
  "name": "envoy.transport_sockets.tls",
  "@type": "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext"
}
```

Interpretación:

```text
micro-a
  │
  ▼
Envoy outbound cluster
  │
  ├── micro-b.demo.svc.cluster.local
  │
  └── UpstreamTlsContext
          │
          ▼
        TLS
```

Combinado con:

```yaml
PeerAuthentication:
  mtls:
    mode: STRICT
```

podemos demostrar que la comunicación está configurada para utilizar mTLS.

---

# 14. Ver upstreams de Envoy

```bash
kubectl exec -n demo \
  "$(kubectl get pods -n demo --no-headers | grep '^micro-a' | awk '{print $1}')" \
  -c istio-proxy -- \
  curl -s http://127.0.0.1:15000/clusters \
  | grep -A20 -B5 'micro-b'
```

Puede aparecer:

```text
outbound|8443||micro-b.demo.svc.cluster.local
```

Significado:

```text
outbound
   ↓
tráfico saliendo del sidecar

8443
   ↓
puerto del Service

micro-b.demo.svc.cluster.local
   ↓
Service destino
```

`EDS` corresponde a Endpoint Discovery Service, mediante el cual Envoy obtiene los endpoints concretos detrás del Service.

---

# 15. Guardar el config_dump completo

```bash
kubectl exec -n demo \
  "$(kubectl get pods -n demo --no-headers | grep '^micro-a' | awk '{print $1}')" \
  -c istio-proxy -- \
  curl -s http://127.0.0.1:15000/config_dump \
  > /tmp/envoy-config.json
```

Importante:

> El `>` se ejecuta en tu máquina local, no dentro del pod.

Buscar:

```bash
grep -n 'micro-b.demo.svc.cluster.local' /tmp/envoy-config.json
```

o:

```bash
grep -n 'UpstreamTlsContext' /tmp/envoy-config.json
```

---

# 16. AuthorizationPolicy

Permitir que `micro-a` llame a `micro-b`:

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

Permitir que `micro-b` llame a `micro-c`:

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: allow-b-to-c
  namespace: demo
spec:
  selector:
    matchLabels:
      app: micro-c
  action: ALLOW
  rules:
  - from:
    - source:
        principals:
        - cluster.local/ns/demo/sa/micro-b
```

Aplicar:

```bash
kubectl apply -f authorization-policy.yaml
```

Verificar:

```bash
kubectl get authorizationpolicy -n demo
```

Matriz deseada:

| Origen | Destino | Resultado |
|---|---|---|
| micro-a | micro-b | ✅ |
| micro-b | micro-c | ✅ |
| micro-a | micro-c | ❌ |
| micro-b | micro-a | ❌ |
| micro-c | micro-a | ❌ |
| micro-c | micro-b | ❌ |

Concepto:

```text
IDENTIDAD
    ↓
ServiceAccount / SPIFFE
    ↓
mTLS
    ↓
AuthorizationPolicy
    ↓
ALLOW / DENY
```

---

# 17. Deny All explícito

Para crear un deny-all explícito:

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: deny-all
  namespace: demo
spec:
  action: DENY
```

Importante:

> `AuthorizationPolicy {}` es un ALLOW vacío, no un deny-all.

Además, las políticas `DENY` tienen precedencia sobre las `ALLOW`.

---

# 18. Prueba de conectividad

Como las imágenes Java incluyen `curl`:

```bash
for pair in "micro-a micro-b" "micro-b micro-c" "micro-c micro-a"; do
  origin=$(echo $pair | awk '{print $1}')
  target=$(echo $pair | awk '{print $2}')

  if output=$(kubectl exec -n demo svc/$origin -- \
    curl -fsS "http://$target:8443/" 2>/dev/null); then
    echo "$output"
  else
    echo "$origin → $target BLOQUEADO"
  fi
done
```

---

# 19. Canary deployment

La aplicación `micro-a` tiene dos versiones:

```text
micro-a v1
micro-a v2
```

Ejemplo:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: canary-micro-a-v1
  namespace: demo
spec:
  replicas: 2
  selector:
    matchLabels:
      app: micro-a
      version: v1
  template:
    metadata:
      labels:
        app: micro-a
        version: v1
    spec:
      serviceAccountName: micro-a
      containers:
      - name: micro-a
        image: roko1987/micro-a:java
        imagePullPolicy: Always
        ports:
        - containerPort: 8443
        env:
        - name: MICRO_B_URL
          value: http://micro-b:8443
        - name: VERSION
          value: "v1"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: canary-micro-a-v2
  namespace: demo
spec:
  replicas: 1
  selector:
    matchLabels:
      app: micro-a
      version: v2
  template:
    metadata:
      labels:
        app: micro-a
        version: v2
    spec:
      serviceAccountName: micro-a
      containers:
      - name: micro-a
        image: roko1987/micro-a:java
        imagePullPolicy: Always
        ports:
        - containerPort: 8443
        env:
        - name: MICRO_B_URL
          value: http://micro-b:8443
        - name: VERSION
          value: "v2"
```

Aplicar:

```bash
kubectl apply -f canary-micro-a.yaml
```

Verificar:

```bash
kubectl get pods -n demo -L version
```

Verificar la variable:

```bash
kubectl exec -n demo <pod-v1> -- printenv VERSION
kubectl exec -n demo <pod-v2> -- printenv VERSION
```

---

# 20. Service para micro-a

El Service utiliza:

```yaml
selector:
  app: micro-a
```

Por lo tanto, incluye:

```text
version=v1
version=v2
```

El routing de Istio será el encargado de seleccionar el subset.

---

# 21. DestinationRule

Ejemplo:

```yaml
apiVersion: networking.istio.io/v1
kind: DestinationRule
metadata:
  name: micro-a
  namespace: demo
spec:
  host: micro-a
  subsets:
  - name: stable
    labels:
      version: v1
  - name: canary
    labels:
      version: v2
  trafficPolicy:
    connectionPool:
      tcp:
        maxConnections: 50
      http:
        http1MaxPendingRequests: 20
        maxRequestsPerConnection: 10
    outlierDetection:
      consecutive5xxErrors: 3
      interval: 5s
      baseEjectionTime: 30s
      maxEjectionPercent: 50
```

Funciones demostradas:

```text
subsets
connectionPool
outlierDetection
```

---

# 22. VirtualService

Ejemplo:

```yaml
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata:
  name: micro-a
  namespace: demo
spec:
  hosts:
  - micro-a
  http:
  - match:
    - headers:
        x-canary:
          exact: "true"
    route:
    - destination:
        host: micro-a
        subset: canary
  - route:
    - destination:
        host: micro-a
        subset: stable
      weight: 95
    - destination:
        host: micro-a
        subset: canary
      weight: 5
```

Resultado:

```text
x-canary: true
       ↓
     v2

tráfico normal
       ↓
95% v1
 5% v2
```

---

# 23. Probar Canary

Tráfico normal:

```bash
for i in {1..20}; do
  echo "=== Request $i ==="
  kubectl exec -n demo svc/micro-a -- \
    curl -s http://micro-a:8443/
  echo
done
```

Forzar Canary:

```bash
for i in {1..10}; do
  kubectl exec -n demo svc/micro-a -- \
    curl -s -H "x-canary: true" http://micro-a:8443/
  echo
done
```

La segunda prueba debe devolver:

```json
"version": "v2"
```

---

# 24. Eliminar Deployment original

Si todavía existe un Deployment original llamado `micro-a`, el Service puede incluir sus pods.

Después de comprobar que las versiones Canary funcionan:

```bash
kubectl delete deployment micro-a -n demo
```

Esto deja únicamente:

```text
canary-micro-a-v1
canary-micro-a-v2
```

---

# 25. Instalar Prometheus

Prometheus proporciona métricas para observar tráfico y comportamiento de Istio.

Agregar repositorio:

```bash
helm repo add prometheus-community \
  https://prometheus-community.github.io/helm-charts

helm repo update
```

Instalar:

```bash
helm install prometheus \
  prometheus-community/prometheus \
  -n istio-system
```

Verificar:

```bash
kubectl get pods -n istio-system | grep prometheus
kubectl get svc -n istio-system | grep prometheus
```

Normalmente el Service será:

```text
prometheus-server
```

Verificar exactamente:

```bash
kubectl get svc -n istio-system prometheus-server
```

---

# 26. Acceder a Prometheus

```bash
kubectl port-forward \
  svc/prometheus-server \
  9090:80 \
  -n istio-system
```

Abrir:

```text
http://localhost:9090
```

---

# 27. Instalar Kiali

Agregar repositorio:

```bash
helm repo add kiali https://kiali.org/helm-charts
helm repo update
```

Instalar para laboratorio:

```bash
helm install kiali-server \
  kiali/kiali-server \
  -n istio-system \
  --set auth.strategy=anonymous
```

Verificar:

```bash
kubectl get pods -n istio-system | grep kiali
kubectl get svc -n istio-system | grep kiali
```

---

# 28. Configurar Kiali para utilizar Prometheus

Actualizar Kiali:

```bash
helm upgrade kiali-server \
  kiali/kiali-server \
  -n istio-system \
  --set auth.strategy=anonymous \
  --set external_services.prometheus.enabled=true \
  --set external_services.prometheus.url=http://prometheus-server.istio-system:80
```

Verificar:

```bash
kubectl rollout status deployment kiali-server -n istio-system
```

Acceder:

```bash
kubectl port-forward \
  svc/kiali \
  20001:20001 \
  -n istio-system
```

Abrir:

```text
http://localhost:20001
```

En Kiali:

```text
Graph
  ↓
Namespace: demo
  ↓
Traffic
  ↓
Versioned app graph
```

---

# 29. Generar tráfico para Kiali

```bash
for i in {1..100}; do
  kubectl exec -n demo svc/micro-a -- \
    curl -s http://micro-a:8443/ > /dev/null
done
```

Luego refrescar Kiali.

Kiali puede mostrar:

```text
micro-a
   │
   ▼
micro-b
   │
   ▼
micro-c
```

y métricas del tráfico.

---

# 30. RBAC

El laboratorio también demuestra Zero Trust a nivel de usuarios humanos.

Usuarios:

```text
developer
admin
```

Los certificados del laboratorio se generan usando la CA del cluster KIND.

Archivos sensibles:

```text
ca.key
developer.key
admin.key
*.csr
```

No deben subirse a Git.

El `.gitignore` debe contener:

```gitignore
rbac/usuarios/
*.key
*.csr
*.srl
```

---

# 31. Contextos RBAC

Developer:

```bash
kubectl config set-credentials developer \
  --client-certificate=developer.crt \
  --client-key=developer.key \
  --embed-certs=true

kubectl config set-context developer@dod-santiago \
  --cluster=kind-dod-santiago \
  --user=developer \
  --namespace=demo
```

Admin:

```bash
kubectl config set-credentials admin \
  --client-certificate=admin.crt \
  --client-key=admin.key \
  --embed-certs=true

kubectl config set-context admin@dod-santiago \
  --cluster=kind-dod-santiago \
  --user=admin \
  --namespace=demo
```

---

# 32. Permisos Developer

Developer puede:

```text
get pods
list pods
watch pods
delete pods
```

No puede:

```text
update deployments
delete deployments
modificar AuthorizationPolicy
```

Importante:

> Kubernetes no tiene un verbo `restart` para Pods. Para un developer, `delete pod` puede utilizarse como una forma controlada de provocar que el Deployment/ReplicaSet cree otro Pod.

Pruebas:

```bash
kubectl auth can-i get pods --as=developer -n demo
kubectl auth can-i delete pods --as=developer -n demo
kubectl auth can-i update deployments --as=developer -n demo
kubectl auth can-i delete authorizationpolicies --as=developer -n demo
```

---

# 33. Permisos Admin

Admin puede administrar recursos de aplicación:

```text
Pods
ReplicaSets
Services
Deployments
```

Pruebas:

```bash
kubectl auth can-i get pods --as=admin -n demo
kubectl auth can-i delete pods --as=admin -n demo

kubectl auth can-i get deployments --as=admin -n demo
kubectl auth can-i create deployments --as=admin -n demo
kubectl auth can-i update deployments --as=admin -n demo
kubectl auth can-i delete deployments --as=admin -n demo
```

---

# 34. GitOps con Argo CD

Application utilizada:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: microservices
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/roko1987-k8s/dod-santiago.git
    targetRevision: main
    path: app
    directory:
      recurse: true
  destination:
    server: https://kubernetes.default.svc
    namespace: demo
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
      allowEmpty: false
    syncOptions:
      - CreateNamespace=true
      - PruneLast=true
```

Concepto:

```text
Git
 │
 │ desired state
 ▼
Argo CD
 │
 │ reconciliation
 ▼
Kubernetes
 │
 ▼
Istio + Java workloads
```

## selfHeal

Si alguien cambia manualmente un recurso:

```bash
kubectl edit deployment ...
```

Argo CD puede reconciliarlo con Git.

## prune

Si un recurso deja de existir en Git, Argo CD puede eliminarlo del cluster durante la reconciliación.

Importante:

> GitOps no impide que una persona con permisos suficientes ejecute `kubectl delete`. GitOps vuelve a reconciliar el estado deseado.

---

# 35. GitOps y seguridad

Si Git es la fuente de verdad, el repositorio también debe protegerse.

Recomendaciones:

```text
Branch protection
Pull Requests
Code Owners
Review obligatorio
No secrets en Git
Secret management
Auditoría
```

Especialmente importante:

> Quien puede modificar manifests de RBAC puede potencialmente escalar privilegios.

---

# 36. Validación completa

## Pods

```bash
kubectl get pods -n demo
```

Esperado:

```text
2/2 Running
```

## Services

```bash
kubectl get svc -n demo
```

## Istio

```bash
kubectl get pods -n istio-system
```

## mTLS

```bash
kubectl get peerauthentication -n demo
```

## Authorization

```bash
kubectl get authorizationpolicy -n demo
```

## Canary

```bash
kubectl get pods -n demo -L version
```

## Prometheus

```bash
kubectl get pods -n istio-system | grep prometheus
```

## Kiali

```bash
kubectl get pods -n istio-system | grep kiali
```

## Argo CD

```bash
kubectl get pods -n argocd
```

---

# 37. Flujo Zero Trust de la demo

La demostración completa sigue este flujo:

```text
                   USER
                    │
                    ▼
               Kubernetes RBAC
                    │
                    ▼
               ServiceAccount
                    │
                    ▼
              SPIFFE Identity
                    │
                    ▼
                 mTLS
                    │
                    ▼
               Envoy Sidecar
                    │
                    ▼
          AuthorizationPolicy
                    │
             ┌──────┴──────┐
             │             │
           ALLOW          DENY
             │             │
             ▼             X
          Workload
```

Ejemplo:

```text
micro-a
  │
  │ identidad:
  │ spiffe://cluster.local/ns/demo/sa/micro-a
  │
  │ mTLS
  ▼
micro-b
  │
  │ AuthorizationPolicy
  │
  ├── micro-a → ALLOW
  ├── micro-c → DENY
  └── desconocido → DENY
```

---

# 38. Evidencias para la presentación

## Evidencia 1 — Identidad

```bash
kubectl exec -n demo <micro-a-pod> \
  -c istio-proxy -- \
  curl -s http://127.0.0.1:15000/certs
```

Mostrar:

```text
spiffe://cluster.local/ns/demo/sa/micro-a
```

## Evidencia 2 — mTLS

```bash
kubectl get peerauthentication -n demo
```

Mostrar:

```text
STRICT
```

## Evidencia 3 — TLS configurado en Envoy

```bash
kubectl exec -n demo <micro-a-pod> \
  -c istio-proxy -- \
  curl -s http://127.0.0.1:15000/config_dump \
  | grep -A80 'outbound|8443||micro-b.demo.svc.cluster.local' \
  | grep -E 'transport_socket|UpstreamTlsContext'
```

Mostrar:

```text
envoy.transport_sockets.tls
UpstreamTlsContext
```

## Evidencia 4 — Autorización

```bash
kubectl get authorizationpolicy -n demo
```

Y probar:

```text
micro-a → micro-b   ✅
micro-b → micro-c   ✅
micro-c → micro-a   ❌
```

## Evidencia 5 — RBAC

```bash
kubectl auth can-i update deployments --as=developer -n demo
```

Resultado:

```text
no
```

Y:

```bash
kubectl auth can-i update deployments --as=admin -n demo
```

Resultado:

```text
yes
```

## Evidencia 6 — Canary

```bash
curl -H "x-canary: true" ...
```

Resultado:

```text
version: v2
```

## Evidencia 7 — Observabilidad

Kiali:

```text
Graph → demo → Versioned app graph
```

---

# 39. Modelo mental de Zero Trust

El laboratorio demuestra que no basta con cifrar el tráfico.

Zero Trust se puede explicar en capas:

```text
1. Identidad
   └── ¿Quién eres?

2. Autenticación
   └── mTLS / certificados / SPIFFE

3. Autorización
   └── ¿Qué puedes hacer?

4. Segmentación
   └── ¿Con qué workload puedes hablar?

5. Least Privilege
   └── Solo los permisos necesarios

6. Observabilidad
   └── ¿Qué está pasando?

7. GitOps / Governance
   └── ¿Quién puede cambiar las reglas?
```

Frase principal:

> **Never Trust, Always Verify.**

---

# 40. Diferencia entre TLS y mTLS

TLS tradicional:

```text
Client ───── TLS ─────► Server
                         │
                    certificado
```

El servidor se autentica ante el cliente.

mTLS:

```text
Client ◄──── mTLS ────► Server
   │                       │
certificado            certificado
   │                       │
   └──── autenticación ────┘
```

Ambos lados presentan identidad.

En Istio:

```text
Workload A
   │
   │ SPIFFE identity
   │
   ▼
Envoy A
   │
   │ mTLS
   ▼
Envoy B
   │
   │
   ▼
Workload B
```

---

# 41. Qué hace cada recurso Istio

| Recurso | Función |
|---|---|
| ServiceAccount | Identidad del workload |
| PeerAuthentication | Define enforcement de mTLS |
| AuthorizationPolicy | Define quién puede acceder |
| DestinationRule | Subsets, connection pool, outlier detection, TLS settings |
| VirtualService | Routing del tráfico |
| Envoy | Data plane / proxy |
| Istiod | Control plane |
| Kiali | Visualización/observabilidad |
| Prometheus | Métricas |

---

# 42. Comandos útiles durante la demo

Ver todos los pods:

```bash
kubectl get pods -A
```

Ver únicamente demo:

```bash
kubectl get pods -n demo -o wide
```

Ver labels:

```bash
kubectl get pods -n demo --show-labels
```

Ver deployments:

```bash
kubectl get deployments -n demo
```

Ver ServiceAccounts:

```bash
kubectl get serviceaccounts -n demo
```

Ver políticas:

```bash
kubectl get peerauthentication,authorizationpolicy -n demo
```

Ver recursos Istio:

```bash
kubectl get virtualservice,destinationrule -n demo
```

Ver eventos:

```bash
kubectl get events -n demo --sort-by=.lastTimestamp
```

---

# 43. Troubleshooting

## Pod no tiene 2 containers

```bash
kubectl get pod -n demo
```

Revisar label:

```bash
kubectl get namespace demo --show-labels
```

Debe existir:

```text
istio-injection=enabled
```

Si se etiquetó después de crear los pods:

```bash
kubectl rollout restart deployment -n demo
```

---

## Kiali dice "Metrics are disabled"

Verificar Prometheus:

```bash
kubectl get pods -n istio-system | grep prometheus
```

Verificar Service:

```bash
kubectl get svc -n istio-system prometheus-server
```

Verificar configuración de Kiali:

```bash
helm get values kiali-server -n istio-system
```

Debe existir:

```yaml
external_services:
  prometheus:
    enabled: true
```

Generar tráfico:

```bash
for i in {1..100}; do
  kubectl exec -n demo svc/micro-a -- \
    curl -s http://micro-a:8443/ > /dev/null
done
```

---

## Argo CD no muestra la aplicación sincronizada

Ver:

```bash
argocd app list
```

o:

```bash
argocd app get microservices
```

También:

```bash
kubectl get applications -n argocd
```

---

## Argo CD login no funciona

Primero iniciar port-forward:

```bash
kubectl -n argocd port-forward svc/argocd-server 8080:443
```

Obtener password:

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d
```

Luego:

```bash
argocd login localhost:8080 --username admin --insecure
```

---

## zsh interpreta mal un comando multilinea

Utilizar una sola barra invertida:

```bash
kubectl ... -- \
  curl ... \
  | grep ...
```

No utilizar:

```text
\\
```

como continuación de línea.

Para evitar problemas, utilizar el comando en una sola línea.

---

# 44. Producción vs laboratorio

Este laboratorio utiliza:

```text
KIND
Certificados manuales para RBAC
Argo CD local
Kiali anonymous
Prometheus Helm chart
```

En producción se recomienda:

```text
Kubernetes administrado
OIDC / Entra ID / Okta / Keycloak
Workload Identity
Secret Manager
GitOps con branch protection
TLS válido
Observabilidad centralizada
Auditoría
HA
Backup
Policy as Code
```

Para AKS:

```text
Microsoft Entra ID
+
Kubernetes RBAC / Azure RBAC
```

Para EKS:

```text
IAM / IAM Identity Center
+
EKS Access Entries
```

No se recomienda gestionar identidades humanas de producción mediante certificados creados manualmente.

---

# 45. Referencias oficiales

Istio Helm:

https://istio.io/latest/docs/setup/install/helm/

Istio Prometheus:

https://istio.io/latest/docs/ops/integrations/prometheus/

Argo CD:

https://argo-cd.readthedocs.io/

Kiali:

https://kiali.org/

Kubernetes:

https://kubernetes.io/

---

# 46. Resumen de la demo

La demo completa puede resumirse en:

```text
                 GIT
                  │
                  ▼
              ARGO CD
                  │
                  ▼
             KUBERNETES
                  │
        ┌─────────┴─────────┐
        │                   │
      RBAC                ISTIO
        │                   │
        │          ┌────────┴────────┐
        │          │                 │
        │        mTLS          Authorization
        │          │                 │
        │          └────────┬────────┘
        │                   │
        │              Envoy Sidecars
        │                   │
        │        ┌──────────┼──────────┐
        │        ▼          ▼          ▼
        │      micro-a    micro-b    micro-c
        │        Java       Java       Java
        │
        └─────────────────────────────────
                         │
                         ▼
                 Prometheus + Kiali
```

## Mensaje final

> **Zero Trust no significa simplemente cifrar el tráfico. Significa establecer identidad, autenticar cada comunicación, autorizar explícitamente cada interacción, aplicar mínimo privilegio, observar el comportamiento y controlar quién puede cambiar las políticas.**

En esta demo:

```text
RBAC
  +
ServiceAccount / SPIFFE
  +
mTLS
  +
AuthorizationPolicy
  +
GitOps
  +
Observabilidad
  +
Traffic Management
```

forman una arquitectura práctica de Zero Trust para microservicios Kubernetes.