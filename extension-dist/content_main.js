// Using js/content.js directly in manifest.json in "content_scripts" will result the following error
// Syntax error: import declarations may only appear at top level of a module. 
// So instead this wrapper js file is used

// Ref: 
// https://stackoverflow.com/questions/48104433/how-to-import-es6-modules-in-content-script-for-chrome-extension#53033388
// https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/web_accessible_resources
// https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Operators/import

// console.log("In Content script of the extension");

async function wrapperInit() {
    
    // All urls are refernce extension root (manifest.json location). 
    // Here we need to use js/content.js
    // as the javascript is under extension-dist/js/content.js
    
    // IMPORTANT: 
    // We need to include 'js/content.js' and all javascripts imported by 'content.js' 
    // in  "web_accessible_resources"

    const src = chrome.runtime.getURL("js/content.js");

    // console.log("Got the Content src ", src);
    
    // Now we use import() fn to import the module dynamically 
    await import(src);
}

wrapperInit();