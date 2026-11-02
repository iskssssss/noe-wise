import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';

const directory=path.resolve(process.argv[2]);
const port=Number(process.argv[3]??18481);
const files=new Map([['/browser-fixture.html',['browser-fixture.html','text/html; charset=utf-8']],['/challenge.png',['challenge.png','image/png']]]);
const server=http.createServer((request,response)=>{
 const item=files.get(new URL(request.url,'http://127.0.0.1').pathname);
 if(request.method!=='GET'||!item){response.writeHead(404);response.end();return;}
 const bytes=fs.readFileSync(path.join(directory,item[0]));response.writeHead(200,{'Content-Type':item[1],'Content-Length':bytes.length,'Cache-Control':'no-store'});response.end(bytes);
});
server.listen(port,'127.0.0.1',()=>console.log(JSON.stringify({status:'ready',url:`http://127.0.0.1:${port}/browser-fixture.html`,pid:process.pid})));
