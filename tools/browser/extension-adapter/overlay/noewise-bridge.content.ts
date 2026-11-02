import {attachPageBridge} from '@/noewise/content';
export default defineContentScript({matches:['https://*/*','http://localhost/*','http://127.0.0.1/*','http://[::1]/*'],allFrames:false,runAt:'document_start',main(){attachPageBridge();}});
