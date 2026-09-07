# Login sculpture

`subaru-supplied.glb` is converted from the user-supplied
`subaru_natsuki_final.3mf`. Original shape and integrated pedestal are preserved.
The source contains one untextured print mesh, no skeleton or animation clips.
An ivory sculpture material is applied; rotation and zoom move the entire object.
No claim of official affiliation or model licensing is made. Confirm asset rights
before redistributing the model publicly.

Rebuild: `node scripts/convert-login-3mf.cjs /absolute/path/to/subaru_natsuki_final.3mf`
(Windows/PowerShell). The converter targets this specific single-mesh project,
changes Z-up to Y-up, centers/scales to 3.4 units tall and calculates vertex normals.
It does not change the original archive. Slicer settings/source paths are excluded.

Deploy login.html, login-character.css/js and assets together. Three.js 0.160.1
and its GLTFLoader are vendored with their MIT license, without runtime CDN access.
The login form remains functional on model load or WebGL failure. Hidden tabs
pause rotation; reduced-motion mode uses manual rotation only.
