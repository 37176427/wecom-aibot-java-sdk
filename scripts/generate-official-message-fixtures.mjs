// Protocol fixtures from the official npm 1.0.7 WSClient implementation.
// Upstream implementation: Copyright (c) 2026 WeCom, MIT.
import fs from 'node:fs';
import vm from 'node:vm';
const bundle = fs.readFileSync(process.argv[2], 'utf8');
const source = bundle.slice(bundle.indexOf('class WSClient extends'), bundle.indexOf('const CRYPTO_CONSTANTS ='));
const context = {EventEmitter: class {}, generateReqId: () => 'generated', WsCmd: {
  RESPONSE_WELCOME: 'aibot_respond_welcome_msg', RESPONSE_UPDATE: 'aibot_respond_update_msg', SEND_MSG: 'aibot_send_msg'
}};
vm.runInNewContext(`${source}\nthis.Client = WSClient;`, context);
const client = Object.create(context.Client.prototype);
const frames = [];
client.wsManager = {sendReply: (id, body, cmd = 'aibot_respond_msg') => { frames.push({cmd, body}); return Promise.resolve(); }};
const frame = {headers: {req_id: 'callback'}};
const image = {msgtype: 'image', image: {base64: 'YWJj', md5: '900150983cd24fb0d6963f7d28e17f72'}};
const card = {card_type: 'button_interaction', task_id: 'task', main_title: {title: 'test'}, button_list: [{text: 'yes', key: 'yes'}]};
await client.replyStream(frame, 'stream', 'final', true, undefined, {id: 'feedback'});
await client.replyStreamWithCard(frame, 'stream', 'combined', true, {msgItem: [image], streamFeedback: {id: 'stream-feedback'}, templateCard: card, cardFeedback: {id: 'card-feedback'}});
await client.replyWelcome(frame, {msgtype: 'text', text: {content: 'welcome'}});
await client.updateTemplateCard(frame, card, ['tester']);
await client.replyMedia(frame, 'video', 'media', {title: 'video', description: 'test'});
await client.sendMediaMessage('chat', 'file', 'media');
await client.sendMessage('chat', {msgtype: 'markdown', markdown: {content: 'notice'}});
await client.replyTemplateCard(frame, card, {id: 'feedback'});
console.log(JSON.stringify({source: '@wecom/aibot-node-sdk@1.0.7', frames}, null, 2));
