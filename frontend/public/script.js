// ======================================================
// PAGE NAVIGATION
// ======================================================

function showApplicationForm() {
    document.getElementById("dashboard")?.classList.remove("active");
    document.getElementById("application-form")?.classList.add("active");
    window.scrollTo(0, 0);
}

function showDashboard() {
    document.getElementById("application-form")?.classList.remove("active");
    document.getElementById("dashboard")?.classList.add("active");
    window.scrollTo(0, 0);
}

// ======================================================
// DOM READY
// ======================================================

document.addEventListener("DOMContentLoaded", () => {
    console.log("CedrusTech Frontend Loaded ✅");

    // ── Elements ─────────────────────────────────────
    const chatInput        = document.getElementById("chatbot-input");
    const sendBtn          = document.getElementById("chatbot-send");
    const messagesContainer = document.getElementById("chatbot-messages");
    const cvUpload         = document.getElementById("cv-upload");
    const fileName         = document.getElementById("file-name");
    const form             = document.getElementById("job-form");

    // ── File name display ─────────────────────────────
    if (cvUpload && fileName) {
        cvUpload.addEventListener("change", () => {
            fileName.textContent =
                cvUpload.files.length > 0 ? cvUpload.files[0].name : "";
        });
    }

    // ======================================================
    // WEBSOCKET
    // FIX: All traffic goes through Java on port 8081.
    //      Java forwards AI questions to Python on port 8000.
    //      The browser never calls Python directly.
    // ======================================================
    const JAVA_WS_URL   = "ws://localhost:8081/ws/chat";
    const JAVA_REST_URL = "http://localhost:8081";

    let socket;
    let reconnectTimer = null;

    function connectWebSocket() {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }

        console.log("Connecting WebSocket to Java backend…", JAVA_WS_URL);
        socket = new WebSocket(JAVA_WS_URL);

        socket.onopen = () => {
            console.log("✅ Connected to CedrusTech AI (Java backend)");
        };

        socket.onmessage = (event) => {
            console.log("RAW SERVER RESPONSE:", event.data);

            // Remove typing indicator
            document.getElementById("typing-message")?.remove();

            let responseText = "No response from AI.";

            try {
                const data = JSON.parse(event.data);

                // FIX: Java sends ApiResponse<> wrapper:
                //   { success: true, message: "...", data: { reply: "..." } }
                // Also handle plain { reply: "..." } for safety.
                responseText =
                    data?.data?.reply   ||   // ApiResponse wrapper (Java WS)
                    data?.reply         ||   // plain reply
                    data?.message       ||   // fallback to message field
                    "Empty response from server.";

            } catch (err) {
                console.error("JSON parse error:", err);
                responseText = typeof event.data === "string"
                    ? event.data
                    : "Invalid response format.";
            }

            appendMessage("bot", responseText);
        };

        socket.onerror = (error) => {
            console.error("WebSocket Error:", error);
        };

        socket.onclose = (event) => {
            console.log("WebSocket Closed ❌", event.code, event.reason);
            // Auto-reconnect after 3 seconds
            reconnectTimer = setTimeout(connectWebSocket, 3000);
        };
    }

    if (messagesContainer) connectWebSocket();

    // ── Append a chat bubble ──────────────────────────
    function appendMessage(role, text) {
        const div = document.createElement("div");
        div.className = `message ${role}`;
        div.textContent = text;
        messagesContainer.appendChild(div);
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }

    // ======================================================
    // SEND CHAT MESSAGE
    // ======================================================
    function sendMessage() {
        if (!chatInput || !messagesContainer) return;

        const text = chatInput.value.trim();
        if (!text) return;

        // Show user bubble immediately
        appendMessage("user", text);

        // Show typing indicator
        const typingMsg = document.createElement("div");
        typingMsg.className = "message bot";
        typingMsg.id        = "typing-message";
        typingMsg.textContent = "CedrusTech AI is typing…";
        messagesContainer.appendChild(typingMsg);
        messagesContainer.scrollTop = messagesContainer.scrollHeight;

        if (socket && socket.readyState === WebSocket.OPEN) {
            // Send plain text — Java wraps it in ChatMessage
            socket.send(text);
        } else {
            console.warn("WebSocket not connected ❌ — trying to reconnect…");
            typingMsg.remove();
            appendMessage("bot", "Connection lost. Reconnecting… please resend.");
            connectWebSocket();
        }

        chatInput.value = "";
    }

    if (sendBtn) sendBtn.addEventListener("click", sendMessage);

    if (chatInput) {
        chatInput.addEventListener("keydown", (e) => {
            if (e.key === "Enter") {
                e.preventDefault();
                sendMessage();
            }
        });
    }

    // ======================================================
    // JOB APPLICATION FORM
    // FIX: Sends to Java backend /apply (port 8081)
    //      Java handles file save + DB via ApplicationService
    // ======================================================
    if (form) {
        form.addEventListener("submit", async (event) => {
            event.preventDefault();

            const firstName   = document.getElementById("first-name")?.value?.trim();
            const lastName    = document.getElementById("last-name")?.value?.trim();
            const email       = document.getElementById("email")?.value?.trim();
            const phone       = document.getElementById("phone")?.value?.trim();
            const department  = document.getElementById("department")?.value;
            const cvFile      = document.getElementById("cv-upload")?.files[0];
            const resumeText  = document.getElementById("resume-text")?.value || "";

            if (!firstName || !lastName || !email || !phone || !department || !cvFile) {
                alert("Please fill all required fields and attach your CV.");
                return;
            }

            const formData = new FormData();
            formData.append("first_name",   firstName);
            formData.append("last_name",    lastName);
            formData.append("email",        email);
            formData.append("phone",        phone);
            formData.append("position_id",  parseInt(department, 10));
            formData.append("resume_text",  resumeText);
            formData.append("cv",           cvFile);

            try {
                console.log("Submitting application to Java backend…");

                // FIX: unified to port 8081 (was 127.0.0.1:8081 before)
                const response = await fetch(`${JAVA_REST_URL}/apply`, {
                    method: "POST",
                    body:   formData
                });

                let data;
                try {
                    data = await response.json();
                } catch {
                    throw new Error("Server did not return valid JSON.");
                }

                console.log("APPLICATION RESPONSE:", data);

                if (response.ok) {
                    alert(data.message || "Application submitted successfully!");
                    form.reset();
                    if (fileName) fileName.textContent = "";
                    showDashboard();
                } else {
                    alert(data.detail || data.message || "Application submission failed.");
                }

            } catch (error) {
                console.error("APPLICATION ERROR:", error);
                alert(`Server connection error: ${error.message}`);
            }
        });
    }
});