# OneKeePass Browser Extension
Browser Extension that can be used with OneKeePass. This is based on native messaging. 

It is a basic version and should work with many sites to provide login credentials stored in KeePass databases

You get the extension for [Firefox](https://addons.mozilla.org/en-US/firefox/addon/onekeepass-browser/) or [Chrome](https://chromewebstore.google.com/detail/onekeepass-browser/cmdmojmbfcpkloflnjkkdjcflaidangh)

## Firefox build
```npm run firefox```

```npx shadow-cljs release extension-dist```

## Chrome build
```npm run chrome```

```npx shadow-cljs release extension-dist```
