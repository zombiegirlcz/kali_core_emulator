#!/usr/bin/env python3
import os
import sys
import json
import urllib.request
import urllib.parse
import subprocess
from http.server import HTTPServer, BaseHTTPRequestHandler

CONFIG_PATH = os.path.expanduser("~/.config/nethunter/agent.json")
PORT = 13338

SYSTEM_PROMPT = """You are the NetHunter AI Operator, a helpful and expert security assistant integrated directly into this mobile Kali Linux / ParrotOS chroot environment.
You have direct terminal access to assist the user. You can execute shell commands, read and write files, control the VPN, and analyze network traffic.
Use your tools when necessary to answer the user's request. Always respond with valid JSON in the following format:

For tool calls:
{
  "thought": "Reasoning about what to do next",
  "tool": "tool_name",
  "arguments": { "arg_name": "arg_value" }
}

For the final answer:
{
  "thought": "Reasoning that I have completed the task",
  "final_answer": "Your final spoken response to the user"
}

Available tools:
1. run_shell_command(command: str) - Runs a bash shell command and returns output.
2. read_file(filepath: str) - Reads file content.
3. write_file(filepath: str, content: str) - Writes content to a file.
4. vpn_control(action: str) - Controls host VPN service. Actions: "start", "stop", "status".
5. analyze_network(filter_ip: str|null, minutes: int) - Analyzes captured VPN traffic logs.
   Returns statistics about connections from the last N minutes (default: 60).
   If filter_ip is provided, shows only traffic to/from that specific IP address.
   Returns: total connections, anomaly count, top destination IPs, top ports, average entropy, protocol breakdown.

Rules:
- Be concise. The final answer will be spoken aloud to the user via TTS.
- If a command takes too long (e.g. ping without count), add parameters to make it non-blocking (e.g. ping -c 3).
- Do not run interactive commands (e.g. nano, top). Use static alternatives (e.g. cat, ps).
- When the user asks about network traffic, connections, IP addresses, threats, anomalies, or security analysis, use the analyze_network tool first.
- Present network analysis results clearly: highlight any CRITICAL or SUSPICIOUS connections, mention top talkers, and suggest actions if anomalies are found.
"""

# Tools implementation
def run_shell_command(command):
    try:
        res = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=15)
        return {
            "stdout": res.stdout,
            "stderr": res.stderr,
            "exit_code": res.returncode
        }
    except subprocess.TimeoutExpired:
        return {"error": "Command timed out after 15 seconds"}
    except Exception as e:
        return {"error": str(e)}

def read_file(filepath):
    try:
        with open(os.path.expanduser(filepath), 'r') as f:
            return {"content": f.read()}
    except Exception as e:
        return {"error": str(e)}

def write_file(filepath, content):
    try:
        path = os.path.expanduser(filepath)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w') as f:
            f.write(content)
        return {"status": "success"}
    except Exception as e:
        return {"error": str(e)}

def vpn_control(action):
    try:
        url = f"http://127.0.0.1:1337/vpn"
        if action == "start":
            req = urllib.request.Request(f"{url}/start", method="POST")
        elif action == "stop":
            req = urllib.request.Request(f"{url}/stop", method="POST")
        else:
            req = urllib.request.Request(url, method="GET")
            
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.loads(response.read().decode('utf-8'))
    except Exception as e:
        return {"error": f"Failed to reach LocalApiServer: {e}"}

def analyze_network(filter_ip=None, minutes=60):
    """Fetch VPN traffic logs and compute statistics for the last N minutes."""
    try:
        url = "http://127.0.0.1:1337/vpn/logs"
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=5) as response:
            logs = json.loads(response.read().decode('utf-8'))
    except Exception as e:
        return {"error": f"Failed to fetch VPN logs: {e}"}

    import time
    now_ms = int(time.time() * 1000)
    cutoff_ms = now_ms - (minutes * 60 * 1000)

    # Filter by time window
    recent = [l for l in logs if l.get("timestamp", 0) >= cutoff_ms]

    # Filter by IP if specified
    if filter_ip:
        recent = [l for l in recent if l.get("srcIp") == filter_ip or l.get("dstIp") == filter_ip]

    if not recent:
        return {
            "total_connections": 0,
            "message": f"No traffic found in the last {minutes} minutes" + (f" for IP {filter_ip}" if filter_ip else ""),
            "anomalies": 0
        }

    # Compute statistics
    total = len(recent)
    anomalies = [l for l in recent if l.get("category") in ("CRITICAL", "SUSPICIOUS")]
    blocked = [l for l in recent if l.get("category") == "BLOCKED"]

    # Top destination IPs
    dst_counts = {}
    for l in recent:
        dst = l.get("dstIp", "unknown")
        dst_counts[dst] = dst_counts.get(dst, 0) + 1
    top_dst = sorted(dst_counts.items(), key=lambda x: x[1], reverse=True)[:5]

    # Top destination ports
    port_counts = {}
    for l in recent:
        port = l.get("dstPort", 0)
        port_counts[port] = port_counts.get(port, 0) + 1
    top_ports = sorted(port_counts.items(), key=lambda x: x[1], reverse=True)[:5]

    # Protocol breakdown
    proto_counts = {}
    for l in recent:
        proto = l.get("protocol", "unknown")
        proto_counts[proto] = proto_counts.get(proto, 0) + 1

    # Average entropy
    entropies = [l.get("entropy", 0) for l in recent if l.get("entropy", 0) > 0]
    avg_entropy = sum(entropies) / len(entropies) if entropies else 0.0

    # Total bytes
    total_sent = sum(l.get("bytesSent", 0) for l in recent)
    total_recv = sum(l.get("bytesReceived", 0) for l in recent)

    # Top apps
    app_counts = {}
    for l in recent:
        app = l.get("appName", "")
        if app:
            app_counts[app] = app_counts.get(app, 0) + 1
    top_apps = sorted(app_counts.items(), key=lambda x: x[1], reverse=True)[:5]

    # Anomaly details
    anomaly_details = []
    for a in anomalies[:10]:  # Limit to 10 most recent anomalies
        anomaly_details.append({
            "src": f"{a.get('srcIp')}:{a.get('srcPort')}",
            "dst": f"{a.get('dstIp')}:{a.get('dstPort')}",
            "protocol": a.get("protocol"),
            "category": a.get("category"),
            "detail": a.get("detail", ""),
            "entropy": round(a.get("entropy", 0), 2),
            "app": a.get("appName", "")
        })

    return {
        "time_window_minutes": minutes,
        "filter_ip": filter_ip,
        "total_connections": total,
        "anomaly_count": len(anomalies),
        "blocked_count": len(blocked),
        "top_destinations": [{"ip": ip, "count": c} for ip, c in top_dst],
        "top_ports": [{"port": p, "count": c} for p, c in top_ports],
        "protocols": proto_counts,
        "average_entropy": round(avg_entropy, 3),
        "bytes_sent": total_sent,
        "bytes_received": total_recv,
        "top_apps": [{"app": a, "count": c} for a, c in top_apps],
        "anomaly_details": anomaly_details
    }

def execute_tool(name, args):
    if name == "run_shell_command":
        return run_shell_command(args.get("command", ""))
    elif name == "read_file":
        return read_file(args.get("filepath", ""))
    elif name == "write_file":
        return write_file(args.get("filepath", ""), args.get("content", ""))
    elif name == "vpn_control":
        return vpn_control(args.get("action", "status"))
    elif name == "analyze_network":
        return analyze_network(
            filter_ip=args.get("filter_ip"),
            minutes=int(args.get("minutes", 60))
        )
    else:
        return {"error": f"Unknown tool: {name}"}

# Config Loader
def load_config():
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, 'r') as f:
                return json.load(f)
        except Exception:
            pass
    return {"provider": "gemini", "key": "", "model": "gemini-2.0-flash"}

# LLM Providers Communication
def call_llm(provider, key, model, messages):
    try:
        if provider == "gemini":
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}"
            # Format messages for Gemini
            contents = []
            for msg in messages:
                role = "user" if msg["role"] in ["user", "system"] else "model"
                contents.append({
                    "role": role,
                    "parts": [{"text": msg["content"]}]
                })
            
            data = json.dumps({"contents": contents}).encode('utf-8')
            req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
            
            with urllib.request.urlopen(req, timeout=20) as res:
                body = json.loads(res.read().decode('utf-8'))
                text = body["candidates"][0]["content"]["parts"][0]["text"]
                return text

        elif provider == "openai":
            url = "https://api.openai.com/v1/chat/completions"
            data = json.dumps({
                "model": model,
                "messages": messages,
                "response_format": {"type": "json_object"}
            }).encode('utf-8')
            req = urllib.request.Request(
                url, data=data,
                headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"}
            )
            with urllib.request.urlopen(req, timeout=20) as res:
                body = json.loads(res.read().decode('utf-8'))
                return body["choices"][0]["message"]["content"]

        elif provider == "anthropic":
            url = "https://api.anthropic.com/v1/messages"
            system_msg = next((m["content"] for m in messages if m["role"] == "system"), "")
            user_messages = [m for m in messages if m["role"] != "system"]
            data = json.dumps({
                "model": model,
                "system": system_msg,
                "messages": [{"role": m["role"], "content": m["content"]} for m in user_messages],
                "max_tokens": 1024
            }).encode('utf-8')
            req = urllib.request.Request(
                url, data=data,
                headers={
                    "Content-Type": "application/json",
                    "x-api-key": key,
                    "anthropic-version": "2023-06-01"
                }
            )
            with urllib.request.urlopen(req, timeout=20) as res:
                body = json.loads(res.read().decode('utf-8'))
                return body["content"][0]["text"]

        elif provider == "ollama":
            url = f"http://127.0.0.1:11434/api/chat"
            data = json.dumps({
                "model": model,
                "messages": messages,
                "stream": False,
                "format": "json"
            }).encode('utf-8')
            req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=20) as res:
                body = json.loads(res.read().decode('utf-8'))
                return body["message"]["content"]

    except Exception as e:
        return json.dumps({"thought": "Error contacting provider", "final_answer": f"API error: {str(e)}"})

def update_status(status_text):
    try:
        with open("/tmp/nethunter_agent_status.json", "w") as f:
            json.dump({"status": status_text}, f)
    except Exception as e:
        try:
            with open("/tmp/status_error.log", "a") as err_f:
                err_f.write(f"Error updating status to '{status_text}': {str(e)}\n")
        except Exception:
            pass

def clear_status():
    try:
        import os
        if os.path.exists("/tmp/nethunter_agent_status.json"):
            os.remove("/tmp/nethunter_agent_status.json")
    except Exception as e:
        try:
            with open("/tmp/status_error.log", "a") as err_f:
                err_f.write(f"Error clearing status: {str(e)}\n")
        except Exception:
            pass

# ReAct Agent loop
def run_agent(query):
    update_status("Thinking...")
    config = load_config()
    provider = config.get("provider", "gemini")
    key = config.get("key", "")
    model = config.get("model", "gemini-2.0-flash")

    if not key and provider != "ollama":
        clear_status()
        return "Please configure your API key first using nethunter-agent-cli config."

    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": query}
    ]

    for step in range(5):  # Limit ReAct loop to 5 steps to avoid infinite loops
        update_status(f"Thinking (step {step+1})...")
        response_text = call_llm(provider, key, model, messages)
        
        # Clean response (strip markdown code block wrappers if any)
        cleaned = response_text.strip()
        if cleaned.startswith("```json"):
            cleaned = cleaned[7:]
        if cleaned.endswith("```"):
            cleaned = cleaned[:-3]
        cleaned = cleaned.strip()

        try:
            decision = json.loads(cleaned)
        except Exception:
            # Fallback in case of raw text response
            clear_status()
            return response_text

        # If it is a final answer, return it
        if "final_answer" in decision:
            clear_status()
            return decision["final_answer"]

        # If LLM wants to call a tool
        if "tool" in decision:
            tool_name = decision["tool"]
            tool_args = decision.get("arguments", {})
            update_status(f"Running: {tool_name}")
            
            # Execute tool
            observation = execute_tool(tool_name, tool_args)
            update_status(f"Processing output from {tool_name}...")
            
            # Append history
            messages.append({"role": "assistant", "content": response_text})
            messages.append({"role": "user", "content": f"Observation from {tool_name}: {json.dumps(observation)}"})
        else:
            break

    clear_status()
    return "Agent loop exceeded maximum steps without a final answer. "

# HTTP Handler
class AgentHTTPHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass # Suppress logging to keep stdin/stdout clean
        
    def do_POST(self):
        parsed_path = urllib.parse.urlparse(self.path).path
        if parsed_path == "/query":
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length).decode('utf-8')
            
            try:
                payload = json.loads(post_data)
                prompt = payload.get("prompt", "")
                
                # Execute agent
                response_str = run_agent(prompt)
                
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                
                res_body = json.dumps({"response": response_str})
                self.wfile.write(res_body.encode('utf-8'))
            except Exception as e:
                self.send_response(500)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()

def speak_text(text):
    try:
        url = "http://127.0.0.1:1337/tts"
        req = urllib.request.Request(url, data=text.encode('utf-8'), method="POST")
        with urllib.request.urlopen(req, timeout=5) as response:
            pass
    except Exception as e:
        print(f"\033[1;31m[-] Failed to speak via TTS: {e}\033[0m")

def run_agent_interactive(query):
    config = load_config()
    provider = config.get("provider", "gemini")
    key = config.get("key", "")
    model = config.get("model", "gemini-2.0-flash")

    if not key and provider != "ollama":
        print("\033[1;31m[-] Error: API key not configured. Run: nethunter-agent-cli config\033[0m")
        return

    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": query}
    ]

    for step in range(5):
        response_text = call_llm(provider, key, model, messages)
        cleaned = response_text.strip()
        if cleaned.startswith("```json"):
            cleaned = cleaned[7:]
        if cleaned.endswith("```"):
            cleaned = cleaned[:-3]
        cleaned = cleaned.strip()

        try:
            decision = json.loads(cleaned)
        except Exception:
            print(f"\033[1;35m[Raw Response]: {response_text}\033[0m")
            speak_text(response_text)
            return

        if "thought" in decision:
            print(f"\033[1;32mThought: {decision['thought']}\033[0m")

        if "final_answer" in decision:
            ans = decision["final_answer"]
            print(f"\n\033[1;32;40mAgent: {ans}\033[0m\n")
            speak_text(ans)
            return

        if "tool" in decision:
            tool_name = decision["tool"]
            tool_args = decision.get("arguments", {})
            print(f"\033[1;36m⚡ Tool Call: {tool_name}({json.dumps(tool_args)})\033[0m")
            
            observation = execute_tool(tool_name, tool_args)
            print(f"\033[1;33m🔍 Observation: {json.dumps(observation, indent=2)}\033[0m")
            
            messages.append({"role": "assistant", "content": response_text})
            messages.append({"role": "user", "content": f"Observation from {tool_name}: {json.dumps(observation)}"})
        else:
            break
    print("\033[1;31m[-] Agent loop exceeded maximum steps without a final answer.\033[0m")

def run_chat_loop():
    print("\033[1;32m====================================================\033[0m")
    print("\033[1;32m      NetHunter AI Operator Chat Console (TUI)     \033[0m")
    print("\033[1;32m====================================================\033[0m")
    print("Commands:")
    print("  Type your message and press Enter.")
    print("  Press Enter on an empty line to speak (Voice Input).")
    print("  Type 'exit' or 'quit' to close.")
    print("\033[1;32m----------------------------------------------------\033[0m")

    while True:
        try:
            user_input = input("\033[1;36mYou: \033[0m").strip()
        except (KeyboardInterrupt, EOFError):
            print("\n\033[1;31mExiting...\033[0m")
            break

        if user_input.lower() in ["exit", "quit"]:
            break

        if user_input == "":
            print("\033[1;33m[🎤 Listening... Speak now]\033[0m")
            try:
                # Trigger voice input from Host LocalApiServer
                req = urllib.request.Request("http://127.0.0.1:1337/voice_input")
                with urllib.request.urlopen(req, timeout=18) as res:
                    body = json.loads(res.read().decode('utf-8'))
                    if "error" in body:
                        print(f"\033[1;31m[-] Speech recognition error: {body['error']}\033[0m")
                        continue
                    text = body.get("text", "").strip()
                    if not text:
                        print("\033[1;31m[-] No speech detected or timeout.\033[0m")
                        continue
                    print(f"\033[1;36mYou (Voice): {text}\033[0m")
                    user_input = text
            except Exception as e:
                print(f"\033[1;31m[-] Speech recognition failed: {e}\033[0m")
                continue

        run_agent_interactive(user_input)

class ReuseHTTPServer(HTTPServer):
    allow_reuse_address = True

def main():
    if len(sys.argv) > 1 and sys.argv[1] == "run-direct":
        # Direct CLI execution for testing
        query = " ".join(sys.argv[2:])
        print(run_agent(query))
    elif len(sys.argv) > 1 and sys.argv[1] == "chat":
        run_chat_loop()
    else:
        # Start daemon HTTP server
        server = ReuseHTTPServer(('127.0.0.1', PORT), AgentHTTPHandler)
        print(f"NetHunter Agent Server listening on port {PORT}...")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass

if __name__ == "__main__":
    main()
