from flask import Flask, jsonify
import os, requests

app = Flask(__name__)

@app.get("/")
def home():
    return jsonify(service="micro-b", message="Hello from Micro B")

@app.get("/health")
def health():
    return jsonify(status="ok", service="micro-b")

@app.get("/call-c")
def call_c():
    url = os.getenv("MICRO_C_URL", "http://micro-c:8443")
    try:
        r = requests.get(url, timeout=3)
        return jsonify(caller="micro-b", downstream=r.json()), r.status_code
    except Exception as e:
        return jsonify(caller="micro-b", error=str(e)), 503

app.run(host="0.0.0.0", port=8443)
