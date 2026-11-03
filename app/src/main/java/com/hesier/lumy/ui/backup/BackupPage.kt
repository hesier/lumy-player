package com.hesier.lumy.ui.backup

import android.content.Context
import com.hesier.lumy.R

/** The browser only transfers bytes; backup passwords remain on the TV. */
internal fun backupPage(context: Context, downloading: Boolean): String {
    fun text(id: Int) = context.getString(id).replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    val title = text(if (downloading) R.string.backup_create else R.string.backup_restore)
    val action = text(if (downloading) R.string.backup_download else R.string.backup_upload)
    val input = if (downloading) "" else """<label for="file">${text(R.string.backup_select_file)}</label><input id="file" type="file" accept=".lumy,.json,application/octet-stream,application/json" required>"""
    return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>$title · Lumy</title>
        <style>
        *{box-sizing:border-box}body{font:17px system-ui,sans-serif;background:#1c1917;color:#fafaf9;margin:0;padding:24px}
        main{max-width:480px;margin:auto}h1{font-size:26px}p{line-height:1.6;color:#d6d3d1}
        label{display:block;margin:20px 0 8px}input,button{width:100%;font:inherit;border-radius:10px;padding:14px}
        input{background:#292524;color:#fafaf9;border:1px solid #78716c}button{margin-top:24px;border:0;background:#fbbf24;color:#1c1917}
        button:disabled{opacity:.5}:focus-visible{outline:3px solid #fbbf24}#status{white-space:pre-wrap}
        </style></head><body><main><h1>$title</h1><p>${text(R.string.backup_web_hint)}</p>
        <form id="form">$input<button id="send" type="submit">$action</button></form>
        <p id="status" role="status" aria-live="polite"></p>
        <template id="success">${text(if (downloading) R.string.backup_download_started else R.string.backup_uploaded)}</template>
        <template id="failure">${text(R.string.backup_web_error)}</template>
        <script>
        const token=location.hash.slice(1),form=document.getElementById('form'),send=document.getElementById('send'),status=document.getElementById('status');
        if(!token){send.disabled=true;status.textContent=document.getElementById('failure').content.textContent;}
        form.addEventListener('submit',async event=>{
          event.preventDefault();send.disabled=true;status.textContent='';
          try{
            const downloading=${downloading};
            let response;
            if(downloading){
              response=await fetch('/backup',{headers:{'X-Lumy-Token':token},credentials:'omit',cache:'no-store'});
            }else{
              const file=document.getElementById('file').files[0];
              if(!file||file.size<1||file.size>2097152)throw new Error();
              response=await fetch('/backup',{method:'POST',headers:{'X-Lumy-Token':token,'Content-Type':'application/octet-stream'},body:file,credentials:'omit',cache:'no-store'});
            }
            if(!response.ok)throw new Error();
            if(downloading){
              const url=URL.createObjectURL(await response.blob()),link=document.createElement('a');
              const filename=(response.headers.get('Content-Disposition')||'').match(/filename="([^"]+)"/);
              link.href=url;link.download=filename?filename[1]:'Lumy-backup.lumy';document.body.appendChild(link);link.click();link.remove();
              setTimeout(()=>URL.revokeObjectURL(url),60000);send.disabled=false;
            }else{form.reset();form.hidden=true;}
            status.textContent=document.getElementById('success').content.textContent;
          }catch(error){status.textContent=document.getElementById('failure').content.textContent;send.disabled=false;}
        });
        </script></main></body></html>
    """.trimIndent()
}
