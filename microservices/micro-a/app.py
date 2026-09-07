from flask import Flask, jsonify
import os, requests

app = Flask(__name__)

@app.get("/")
def home():
    return jsonify(service="micro-a", message="Hello from Micro A")

@app.get("/health")
def health():
    return jsonify(status="ok", service="micro-a")

@app.get("/call-b")
def call_b():
    url = os.getenv("MICRO_B_URL", "http://micro-b:8443")
    try:
        r = requests.get(url, timeout=3)
        return jsonify(caller="micro-a", downstream=r.json()), r.status_code
    except Exception as e:
        return jsonify(caller="micro-a", error=str(e)), 503

app.run(host="0.0.0.0", port=8443)
