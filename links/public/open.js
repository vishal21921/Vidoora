"use strict";

const match = location.pathname.match(/^\/watch\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i);
const token = location.hash.slice(1);
const valid = match && !location.search && token.length <= 4096 &&
  /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(token);
const message = document.getElementById("message");

if (valid) {
  const id = match[1].toLowerCase();
  const open = document.getElementById("open");
  const store = document.getElementById("install").href;
  const feedback = document.getElementById("feedback");
  open.href = "intent://watch/" + id + "#Intent;scheme=vidoora;package=com.vidoora.app;" +
    "S.vidoora_share_token=" + encodeURIComponent(token) + ";" +
    "S.browser_fallback_url=" + encodeURIComponent(store) + ";end";
  open.hidden = false;
  document.getElementById("return").hidden = false;

  document.getElementById("copy").addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(location.origin + "/watch/" + id + "#" + token);
      feedback.textContent = "Link copied. Paste it in Vidoora.";
    } catch {
      feedback.textContent = "Copy the complete link from your browser’s address bar.";
    }
  });
} else if (location.pathname.startsWith("/watch")) {
  document.getElementById("title").textContent = "Video link incomplete";
  message.textContent = "Ask the uploader for the complete link, then open it again.";
} else {
  message.textContent = "Install Vidoora and open a video link shared with you.";
}
// Keep the share capability in the fragment and the app launch intent. The store URL stays generic.
