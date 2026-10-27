package com.hesier.lumy.ui.login

import android.content.Context
import com.hesier.lumy.R

/** Self-contained page: no remote scripts, fonts, analytics or storage. */
internal fun phoneInputPage(context: Context): String {
    fun text(id: Int) = context.getString(id).replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${text(R.string.phone_input_title)}</title>
        <style>
        *{box-sizing:border-box}body{font:17px system-ui,sans-serif;background:#1c1917;color:#fafaf9;margin:0;padding:24px}
        main{max-width:480px;margin:auto}h1{font-size:26px}p{line-height:1.6;color:#d6d3d1}
        label{display:block;margin:20px 0 6px}input,button{width:100%;font:inherit;border-radius:10px;padding:14px}
        input{background:#292524;color:#fafaf9;border:1px solid #78716c}button{margin-top:24px;border:0;background:#fbbf24;color:#1c1917}
        button:disabled{opacity:.5}:focus-visible{outline:3px solid #fbbf24}#status{white-space:pre-wrap}
        </style></head><body><main><h1>${text(R.string.phone_input_title)}</h1>
        <p>${text(R.string.phone_input_web_hint)}</p><form id="form" autocomplete="off">
        <label for="address">${text(R.string.direct_login_address)}</label>
        <input id="address" name="address" required maxlength="2048" inputmode="url" autocapitalize="none" spellcheck="false" placeholder="https://example.com:8096">
        <label for="username">${text(R.string.direct_login_username)}</label>
        <input id="username" name="username" required maxlength="256" autocapitalize="none" spellcheck="false">
        <label for="password">${text(R.string.direct_login_password)}</label>
        <input id="password" name="password" type="password" maxlength="4096" autocomplete="new-password">
        <label for="name">${text(R.string.direct_login_name)}</label>
        <input id="name" name="name" maxlength="128">
        <button id="send" type="submit">${text(R.string.phone_input_send)}</button></form>
        <p id="status" role="status" aria-live="polite"></p>
        <template id="success">${text(R.string.phone_input_sent)}</template>
        <template id="failure">${text(R.string.phone_input_web_error)}</template>
        <script>
        const token=location.hash.slice(1),form=document.getElementById('form'),send=document.getElementById('send'),status=document.getElementById('status');
        if(!token){send.disabled=true;status.textContent=document.getElementById('failure').content.textContent;}
        form.addEventListener('submit',async event=>{
          event.preventDefault();send.disabled=true;status.textContent='';
          try{
            const body=new URLSearchParams(new FormData(form));
            const response=await fetch('/submit',{method:'POST',headers:{'X-Lumy-Token':token},body,credentials:'omit',cache:'no-store'});
            if(!response.ok)throw new Error();
            form.reset();form.hidden=true;status.textContent=document.getElementById('success').content.textContent;
          }catch(error){status.textContent=document.getElementById('failure').content.textContent;send.disabled=false;}
        });
        </script></main></body></html>
    """.trimIndent()
}
