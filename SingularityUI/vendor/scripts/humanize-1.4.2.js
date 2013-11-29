(function(){var e,t,n,r,i,s,o;i=new function(){},o=i.toString,r=function(e){return e!==e},n=function(e){return((typeof window!="undefined"&&window!==null?window.isFinite:void 0)||global.isFinite)(e)&&!r(parseFloat(e))},t=function(e){return o.call(e)==="[object Array]"},s=[{name:"second",value:1e3},{name:"minute",value:6e4},{name:"hour",value:36e5},{name:"day",value:864e5},{name:"week",value:6048e5}],e={},e.intword=function(t,n,r){return r==null&&(r=2),e.compactInteger(t,r)},e.compactInteger=function(e,t){var n,r,i,s,o,u,a,f,l,c,h,p,d,v,m,g,y,b,w;t==null&&(t=0),t=Math.max(t,0),u=parseInt(e,10),h=u<0?"-":"",p=Math.abs(u),v=""+p,a=v.length,f=[13,10,7,4],n=["T","B","M","k"];if(p<1e3)return t>0&&(v+="."+Array(t+1).join("0")),""+h+v;if(a>f[0]+3)return u.toExponential(t).replace("e+","x10^");for(y=0,b=f.length;y<b;y++){w=f[y];if(a>=w){o=w;break}}return r=a-o+1,d=v.split(""),g=d.slice(0,r),s=d.slice(r,r+t+1),m=g.join(""),i=s.join(""),i.length<t&&(i+=""+Array(t-i.length+1).join("0")),t===0?l=""+h+m+n[f.indexOf(o)]:(c=(+(""+m+"."+i)).toFixed(t),l=""+h+c+n[f.indexOf(o)]),l},e.intcomma=e.intComma=function(t,n){return n==null&&(n=0),e.formatNumber(t,n)},e.filesize=e.fileSize=function(t){var n;return t>=1073741824?n=e.formatNumber(t/1073741824,2,"")+" GB":t>=1048576?n=e.formatNumber(t/1048576,2,"")+" MB":t>=1024?n=e.formatNumber(t/1024,0)+" KB":n=e.formatNumber(t,0)+e.pluralize(t," byte"),n},e.formatNumber=function(t,n,r,i){var s,o,u,a,f,l,c,h=this;return n==null&&(n=0),r==null&&(r=","),i==null&&(i="."),a=function(e,t,n){return n?e.substr(0,n)+t:""},o=function(e,t,n){return e.substr(n).replace(/(\d{3})(?=\d)/g,"$1"+t)},u=function(t,n,r){return r?n+e.toFixed(Math.abs(t),r).split(".")[1]:""},c=e.normalizePrecision(n),l=t<0&&"-"||"",s=parseInt(e.toFixed(Math.abs(t||0),c),10)+"",f=s.length>3?s.length%3:0,l+a(s,r,f)+o(s,r,f)+u(t,i,c)},e.toFixed=function(t,n){var r;return n==null&&(n=e.normalizePrecision(n,0)),r=Math.pow(10,n),(Math.round(t*r)/r).toFixed(n)},e.normalizePrecision=function(e,t){return e=Math.round(Math.abs(e)),r(e)?t:e},e.ordinal=function(e){var t,n,r,i;r=parseInt(e,10);if(r===0)return e;i=r%100;if(i===11||i===12||i===13)return""+r+"th";n=r%10;switch(n){case 1:t="st";break;case 2:t="nd";break;case 3:t="rd";break;default:t="th"}return""+r+t},e.times=function(e,t){var r,i,s;t==null&&(t={});if(n(e)&&e>=0)return r=parseFloat(e),i=["never","once","twice"],t[r]!=null?""+t[r]:""+(((s=i[r])!=null?s.toString():void 0)||r.toString()+" times")},e.pluralize=function(e,t,n){if(e==null||t==null)return;return n==null&&(n=t+"s"),parseInt(e,10)===1?t:n},e.truncate=function(e,t,n){return t==null&&(t=100),n==null&&(n="..."),e.length>t?e.substring(0,t-n.length)+n:e},e.truncatewords=e.truncateWords=function(e,t){var n,r,i;n=e.split(" "),i="",r=0;while(r<t)n[r]!=null&&(i+=""+n[r]+" "),r++;if(n.length>t)return i+="..."},e.truncatenumber=e.boundedNumber=function(e,t,r){var i;return t==null&&(t=100),r==null&&(r="+"),i=null,n(e)&&n(t)&&e>t&&(i=t+r),(i||e).toString()},e.oxford=function(t,n,r){var i,s,o;return o=t.length,o<2?""+t:o===2?t.join(" and "):(n!=null&&o>n?(i=o-n,s=n,r==null&&(r=", and "+i+" "+e.pluralize(i,"other"))):(s=-1,r=", and "+t[o-1]),t.slice(0,s).join(", ")+r)},e.dictionary=function(e,t,n){var r,i,s,o;t==null&&(t=" is "),n==null&&(n=", "),s="";if(e!=null&&typeof e=="object"&&Object.prototype.toString.call(e)!=="[object Array]"){r=[];for(i in e)o=e[i],r.push(""+i+t+o);s=r.join(n)}return s},e.frequency=function(n,r){var i,s,o;if(!t(n))return;return i=n.length,o=e.times(i),i===0?s=""+o+" "+r:s=""+r+" "+o,s},e.pace=function(t,n,r){var i,o,u,a,f,l,c,h;r==null&&(r="time");if(t===0||n===0)return"No "+e.pluralize(r);o="Approximately",l=null,u=t/n;for(c=0,h=s.length;c<h;c++){i=s[c],a=u*i.value;if(a>1){l=i.name;break}}return l||(o="Less than",a=1,l=s[s.length-1].name),f=Math.round(a),r=e.pluralize(f,r),""+o+" "+f+" "+r+" per "+l},e.nl2br=function(e,t){return t==null&&(t="<br/>"),e.replace(/\n/g,t)},e.br2nl=function(e,t){return t==null&&(t="\r\n"),e.replace(/\<br\s*\/?\>/g,t)},e.capitalize=function(e,t){return t==null&&(t=!1),""+e.charAt(0).toUpperCase()+(t?e.slice(1).toLowerCase():e.slice(1))},e.capitalizeAll=function(e){return e.replace(/(?:^|\s)\S/g,function(e){return e.toUpperCase()})},e.titlecase=e.titleCase=function(t){var n,r,i,s,o,u=this;return i=/\b(a|an|and|at|but|by|de|en|for|if|in|of|on|or|the|to|via|vs?\.?)\b/i,r=/\S+[A-Z]+\S*/,o=/\s+/,s=/-/,n=function(t,u,a){var f,l,c,h,p,d;u==null&&(u=!1),a==null&&(a=!0),c=[],l=t.split(u?s:o);for(f=p=0,d=l.length;p<d;f=++p){h=l[f];if(h.indexOf("-")!==-1){c.push(n(h,!0,f===0||f===l.length-1));continue}if(!(!a||f!==0&&f!==l.length-1)){c.push(r.test(h)?h:e.capitalize(h));continue}r.test(h)?c.push(h):i.test(h)?c.push(h.toLowerCase()):c.push(e.capitalize(h))}return c.join(u?"-":" ")},n(t)},this.Humanize=e,typeof module!="undefined"&&module!==null&&(module.exports=e)}).call(this);