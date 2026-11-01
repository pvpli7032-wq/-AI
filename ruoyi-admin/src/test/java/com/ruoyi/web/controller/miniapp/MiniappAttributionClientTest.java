package com.ruoyi.web.controller.miniapp;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Runs the shipped miniapp modules in Node with WeChat I/O doubles; no network or WeChat account is used. */
public class MiniappAttributionClientTest {
    @Test public void tokenIdentitySharingAndFirstAuthorization() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("tbk-miniapp"))) root = root.getParent();
        assertNotNull("Repository root containing tbk-miniapp is required", root);
        Path output = Files.createTempFile("line9-miniapp-", ".log");
        try {
            Process process = new ProcessBuilder("node", "-e", SCRIPT, root.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            assertTrue("Miniapp test timed out", finished);
            String log = Files.readString(output);
            assertEquals(log, 0, process.exitValue());
            assertTrue(log, log.contains("miniapp attribution checks passed"));
        } finally { Files.deleteIfExists(output); }
    }

    private static final String SCRIPT = """
        const assert = require('node:assert/strict');
        const fs = require('node:fs');
        const path = require('node:path');
        const vm = require('node:vm');
        const root = process.argv[1];
        const app = {globalData:{apiUrl:'https://local.invalid',referralCode:'',currentGoods:null},login:()=>{}};
        const storage = new Map();
        const calls = [];
        const wx = {
          getStorageSync:key=>storage.get(key), setStorageSync:(key,v)=>storage.set(key,v),
          removeStorageSync:key=>storage.delete(key), showLoading:()=>{}, hideLoading:()=>{}, showToast:()=>{},
          request:options=>{calls.push(options);options.success({statusCode:200,data:{code:200,data:{id:'123',title:'test'}}});}
        };
        const silent = {log:()=>{},error:()=>{},warn:()=>{}};
        function load(relative, mocks={}, onPage) {
          const filename=path.join(root,relative), module={exports:{}};
          vm.runInNewContext(fs.readFileSync(filename,'utf8'),{
            module, exports:module.exports, wx, getApp:()=>app, Page:onPage, console:silent,
            require:name=>Object.hasOwn(mocks,name)?mocks[name]:require(path.resolve(path.dirname(filename),name)),
          },{filename});
          return module.exports;
        }
        (async()=>{
          const request=load('tbk-miniapp/utils/request.js',{'./auth.js':{getToken:()=>storage.get('token')||''}});
          await request.optionalGet('/miniapp/tbk/goods/123',{});
          assert.equal(calls.at(-1).header.Authorization,undefined);
          storage.set('token','current-viewer-token');
          await request.optionalGet('/miniapp/tbk/goods/123',{});
          assert.equal(calls.at(-1).header.Authorization,'Bearer current-viewer-token');
          await request.publicGet('/miniapp/tbk/categories',{});
          assert.equal(calls.at(-1).header.Authorization,undefined);

          const api=load('tbk-miniapp/api/platform-api.js',{
            '../utils/request.js':request,
            '../utils/tbk-adapter.js':{getAdapter:()=>({adaptGoodsList:r=>r.data,adaptGoodsDetail:r=>r.data,adaptTpwdResponse:r=>r})}
          });
          await api.getGoodsList('tbk',{keyword:'search-word',userId:999,pid:'other-pid',invitationCode:'other-code',subUnionId:'other-token'});
          assert.equal(calls.at(-1).header.Authorization,'Bearer current-viewer-token');
          assert.equal(calls.at(-1).data.keyword,'search-word');
          for(const key of ['userId','pid','invitationCode','subUnionId']) assert.equal(calls.at(-1).data[key],undefined);
          await api.getTpwd('tbk',{goodsId:'123',text:'title',url:'https://other.invalid/?pid=wrong',userId:999,invitationCode:'wrong'});
          assert.equal(calls.at(-1).data.goodsId,'123');
          assert.equal(calls.at(-1).data.url,undefined);
          assert.equal(calls.at(-1).header.Authorization,'Bearer current-viewer-token');
          for(const platform of ['jd','dtk','pdd']) {
            await api.getTpwd(platform,{goodsId:'123',goodsSign:'test-sign',userId:999,invitationCode:'wrong'});
            assert.equal(calls.at(-1).header.Authorization,'Bearer current-viewer-token');
            assert.equal(calls.at(-1).data.userId,undefined);
            assert.equal(calls.at(-1).data.invitationCode,undefined);
          }
          storage.delete('token');
          await api.getGoodsList('tbk',{});
          assert.equal(calls.at(-1).header.Authorization,undefined);
          await api.getGoodsDetail('dtk',{id:'123'});
          assert.ok(calls.at(-1).url.endsWith('/miniapp/dtk/goods/123'));
          await api.getGoodsDetail('pdd',{id:'test-sign'});
          assert.equal(calls.at(-1).data.goodsIdList,'["test-sign"]');

          let page;
          load('tbk-miniapp/pages/goods/detail/detail.js',{
            '../../../utils/request.js':request,'../../../api/platform-api.js':api
          },value=>page=value);
          page.data={...page.data,goodsId:'123&userId=999',currentPlatform:'tbk',isAgent:true,invitationCode:'sender-code',
            goods:{title:'test',mainPic:'https://image.invalid/test.png',commission:'99',estimatedRebate:'5.00',clickUrl:'https://link.invalid/?pid=sender',couponShareUrl:'https://link.invalid/?pid=sender'}};
          const card=page.onShareAppMessage(), timeline=page.onShareTimeline();
          for(const query of [card.path.split('?')[1],timeline.query]) {
            const params=new URLSearchParams(query);
            assert.deepEqual([...params.keys()].sort(),['id','platform']);
            assert.equal(params.get('id'),'123&userId=999');
          }
          assert.equal(page.estimatedRebate({commission:'99.00'}),null);
          assert.equal(page.estimatedRebate({shownRebate:'1.23'}),'1.23');
          assert.equal(page.estimatedRebate({estimatedRebate:0}),'0');
          app.globalData.currentGoods={id:'123',commission:'99',clickUrl:'https://old.invalid/?pid=sender'};
          page.setData=values=>Object.assign(page.data,values);
          let refreshed=false;
          page.loadGoodsDetail=()=>{refreshed=true;};
          page.onLoad({id:'123',platform:'tbk',commission:'99',couponShareUrl:'https://sender.invalid',invitationCode:'sender'});
          assert.equal(app.globalData.currentGoods,null);
          assert.equal(refreshed,true);
          const markup=fs.readFileSync(path.join(root,'tbk-miniapp/pages/goods/detail/detail.wxml'),'utf8');
          assert.ok(markup.includes('预计返利'));
          assert.ok(!markup.includes('{{goods.commission}}'));

          let home;
          load('tbk-miniapp/pages/index/index.js',{'../../utils/request.js':request,'../../api/platform-api.js':api},value=>home=value);
          home.data={...home.data,isAgent:true,invitationCode:'sender-code'};
          assert.equal(home.onShareAppMessage().path,'/pages/index/index');

          let loginData;
          const auth=load('tbk-miniapp/utils/auth.js',{'./request.js':{publicPostForm:async(url,data)=>{
            loginData=data;return {code:200,token:'local-token',user:{userId:123},invitationCode:'own-code'};
          }}});
          app.globalData.referralCode='first-inviter';
          storage.set('referralCode','first-inviter');
          await auth.doLogin('local-code',null);
          assert.equal(loginData.invitationCode,'first-inviter');
          assert.equal(storage.has('referralCode'),false);
          assert.equal(app.globalData.referralCode,'');
          app.globalData.referralCode='later-inviter';
          await auth.doLogin('local-code',null);
          assert.equal(loginData.invitationCode,undefined);
          console.log('miniapp attribution checks passed');
        })().catch(error=>{console.error(error);process.exitCode=1;});
        """;
}
