# OneKeePass Browser Extension
Browser Extension that can be used with OneKeePass. This is based on native messaging. 

It is a basic version and should work with many sites to provide login credentials stored in KeePass databases

You get the extension for [Firefox](https://addons.mozilla.org/en-US/firefox/addon/onekeepass-browser/) or [Chrome](https://chromewebstore.google.com/detail/onekeepass-browser/cmdmojmbfcpkloflnjkkdjcflaidangh)

See the  [CHANGELOG](docs/CHANGELOG.md) for release details

## User Guide

A basic [User Guide ](docs/user-guide.md) is available explaining how to use this extension with OneKeePass desktop app


## Build

### Tests

With Java and Node.js installed, run `npm ci` followed by `npm test`.
The Node test suite covers autofill frame/document routing, navigation invalidation,
entry selection authorization, and the popup MessageChannel handshake. It uses
synthetic URLs and credentials and does not access a browser profile or database.

### Firefox build
```npm run firefox```

```npx shadow-cljs release extension-dist```

### Chrome build
```npm run chrome```

```npx shadow-cljs release extension-dist```
