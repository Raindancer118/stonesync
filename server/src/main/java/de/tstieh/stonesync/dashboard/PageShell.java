package de.tstieh.stonesync.dashboard;

/**
 * The one shared HTML/CSS shell for every browser-facing page (dashboard, vault viewer, invite
 * onboarding) - see {@code Design.md} at the repo root for the design decisions this encodes.
 * No templating engine is configured for this project, so this is a plain string template; every
 * page-specific piece of content is still built and HTML-escaped by its own controller (see
 * {@code HtmlEscaper}) and only assembled here.
 */
public final class PageShell {

    private PageShell() {
    }

    private static final String STYLE = """
            :root{
              --bg:#17151b; --surface:#1f1c24; --surface-raised:#272330; --stroke:#37323f;
              --text:#ece7e2; --text-muted:#a79fb0;
              --accent:#9b7fc4; --accent-strong:#b699dd;
              --warm:#c98a4b; --danger:#c4715f;
            }
            *{box-sizing:border-box;}
            html,body{margin:0;}
            body{
              background:var(--bg); color:var(--text);
              font-family:"Source Sans 3", sans-serif; font-size:16px; line-height:1.5;
              position:relative; min-height:100vh;
            }
            body::before{
              content:""; position:fixed; inset:0; pointer-events:none; z-index:0;
              opacity:.05; mix-blend-mode:overlay;
              background-image:url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='120' height='120'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/></filter><rect width='100%25' height='100%25' filter='url(%23n)'/></svg>");
            }
            h1,h2,h3{font-family:"Fraunces", serif; font-weight:600; letter-spacing:-0.01em; margin:0 0 .5rem;}
            a{color:var(--accent-strong); text-decoration:none;}
            a:hover{text-decoration:underline;}
            code, .mono{font-family:"IBM Plex Mono", monospace;}

            .shell{display:flex; min-height:100vh; position:relative; z-index:1;}
            .rail{
              width:15rem; flex:0 0 auto; background:var(--surface); border-right:1px solid var(--stroke);
              padding:1.5rem 1.25rem; display:flex; flex-direction:column; gap:.35rem;
            }
            .rail .brand{font-family:"Fraunces",serif; font-size:1.15rem; font-weight:600; margin-bottom:1.25rem; color:var(--text);}
            .rail .brand em{color:var(--accent); font-style:normal;}
            .rail a{color:var(--text-muted); font-size:.92rem; padding:.4rem .5rem; border-radius:8px;}
            .rail a:hover{background:var(--surface-raised); color:var(--text); text-decoration:none;}
            .rail .crumb{color:var(--text-muted); font-size:.78rem; margin:1.25rem 0 .25rem; text-transform:uppercase; letter-spacing:.08em;}

            .shell{justify-content:center;}
            main{flex:1 1 auto; padding:2.5rem 3rem 4rem; max-width:44rem; margin:0 auto;}
            section{margin-bottom:3.5rem;}
            section > .label{color:var(--text-muted); font-size:.78rem; text-transform:uppercase; letter-spacing:.08em; margin-bottom:.75rem;}

            .hero-search{
              text-align:center; margin-bottom:2.5rem;
              min-height:min(70vh, 34rem); display:flex; flex-direction:column; align-items:center;
              justify-content:center;
            }
            .hero-search .wordmark{font-family:"Fraunces",serif; font-size:3rem; font-weight:600; margin-bottom:2rem;}
            .hero-search .wordmark em{color:var(--accent); font-style:normal;}
            .hero-search form{
              display:flex; align-items:center; gap:.6rem; background:var(--surface-raised);
              border:1px solid var(--stroke); border-radius:999px; padding:.6rem .6rem .6rem 2rem;
              box-shadow:0 16px 44px -16px rgba(0,0,0,.6); transition:border-color .15s, box-shadow .15s;
              width:100%; max-width:40rem; margin:0 auto;
            }
            .hero-search form:focus-within{border-color:var(--accent); box-shadow:0 0 0 4px rgba(155,127,196,.22), 0 16px 44px -16px rgba(0,0,0,.6);}
            .hero-search input{flex:1; background:none; border:none; padding:1rem .2rem; font-size:1.3rem; color:var(--text);}
            .hero-search input:focus{outline:none;}
            .hero-search .icon-btn{
              display:flex; align-items:center; justify-content:center; width:3.4rem; height:3.4rem;
              border-radius:999px; background:var(--accent); color:var(--bg); border:none; cursor:pointer;
              flex:0 0 auto; font-size:1.4rem; transition:background-color .15s;
            }
            .hero-search .icon-btn:hover{background:var(--accent-strong);}
            .hero-search .hint{color:var(--text-muted); font-size:.88rem; margin-top:1.1rem;}

            .panel{background:var(--surface); border:1px solid var(--stroke); padding:1.25rem 1.5rem; border-radius:16px;}

            .btn{
              display:inline-flex; align-items:center; gap:.4rem; background:var(--accent); color:var(--bg);
              border:none; padding:.6rem 1.2rem; border-radius:999px; font-weight:600; font-size:.92rem;
              cursor:pointer; transition:background-color .15s, box-shadow .15s, transform .15s;
              box-shadow:0 4px 14px -6px rgba(155,127,196,.55);
            }
            .btn:hover{background:var(--accent-strong); box-shadow:0 6px 18px -6px rgba(182,153,221,.65); transform:translateY(-1px);}
            input[type=text], input[type=email], select{
              background:var(--surface-raised); border:1px solid var(--stroke); color:var(--text);
              padding:.6rem .9rem; border-radius:12px; font-family:inherit; font-size:.92rem;
              transition:border-color .15s;
            }
            input:focus, select:focus{outline:none; border-color:var(--accent);}

            .role-badge{
              font-family:"IBM Plex Mono",monospace; font-size:.72rem; padding:.25rem .65rem; border-radius:999px;
              background:var(--surface-raised); letter-spacing:.03em;
            }
            .role-owner{color:var(--warm);}
            .role-editor{color:var(--accent-strong);}
            .role-viewer{color:var(--text-muted);}

            .vault-row{
              display:flex; align-items:center; justify-content:space-between; gap:1rem;
              padding:1.1rem 1.4rem; border:1px solid var(--stroke); margin-bottom:.9rem; border-radius:18px;
              background:var(--surface); transition:border-color .15s, box-shadow .15s;
            }
            .vault-row:hover{border-color:var(--accent); box-shadow:0 8px 24px -12px rgba(0,0,0,.5);}
            .vault-row .name{font-family:"Fraunces",serif; font-weight:600; font-size:1.05rem;}
            .invite-form{display:flex; gap:.6rem; margin-top:.9rem; flex-wrap:wrap; align-items:center;}
            .invite-form input{flex:1; min-width:12rem;}

            .file-row{
              display:flex; align-items:center; gap:.6rem; padding:.55rem .8rem;
              font-family:"IBM Plex Mono",monospace; font-size:.88rem; border-radius:10px;
              color:var(--text-muted); transition:background-color .15s, color .15s;
            }
            .file-row:hover{background:var(--surface-raised); color:var(--text);}
            .file-row .glyph{opacity:.7; width:1.1em;}
            .file-row a{color:inherit;}
            .file-row:hover a{color:var(--text);}

            .search-box{display:flex; gap:.6rem; margin-bottom:1.5rem;}
            .search-box input{flex:1;}
            .hit{padding:1rem 1.2rem; border:1px solid var(--stroke); border-radius:14px; margin-bottom:.7rem; background:var(--surface); transition:border-color .15s;}
            .hit:hover{border-color:var(--accent);}
            .hit .path{font-family:"IBM Plex Mono",monospace; font-size:.88rem; color:var(--accent-strong);}
            .hit .snippet{color:var(--text-muted); margin:.4rem 0 0; font-size:.92rem;}
            mark{background:rgba(201,138,75,.28); color:var(--text); padding:.05em .3em; border-radius:5px;}

            article.note{max-width:65ch; line-height:1.65; background:var(--surface); border:1px solid var(--stroke); border-radius:18px; padding:2rem 2.5rem;}
            article.note h1{font-size:1.7rem;}
            article.note h2{font-size:1.3rem; margin-top:1.5rem;}
            article.note p{margin:.8rem 0;}
            article.note code{background:var(--bg); border:1px solid var(--stroke); padding:.15em .45em; border-radius:6px; font-size:.88em;}
            article.note pre{background:var(--bg); border:1px solid var(--stroke); padding:1rem 1.2rem; border-radius:14px; overflow-x:auto;}

            .centered{display:flex; align-items:center; justify-content:center; min-height:100vh; padding:2rem;}
            .card{background:var(--surface); border:1px solid var(--stroke); border-radius:20px; padding:2.5rem 3rem; max-width:32rem; text-align:center;}
            .card ol, .card ul{text-align:left;}
            .card .lede{color:var(--text-muted);}
            .card code{background:var(--bg); border:1px solid var(--stroke); padding:.15em .45em; border-radius:6px; font-size:.9em;}

            @media (prefers-reduced-motion: reduce){ *{transition:none !important;} }
            @media (max-width: 760px){ .rail{display:none;} main{padding:1.5rem;} }
            """;

    private static final String HEAD = """
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,400..700&family=Source+Sans+3:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap" rel="stylesheet">
            <style>
            %s
            </style>
            """.formatted(STYLE);

    /** Full shell with the left sidebar - the dashboard and vault viewer pages. */
    public static String page(String title, String railHtml, String mainHtml) {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"/><title>%s</title>
                <meta name="viewport" content="width=device-width, initial-scale=1"/>
                %s
                </head><body>
                <div class="shell">
                  <nav class="rail">%s</nav>
                  <main>%s</main>
                </div>
                </body></html>
                """.formatted(title, HEAD, railHtml, mainHtml);
    }

    /** No sidebar, a single centered card - login/invite/connect onboarding pages. */
    public static String centered(String title, String cardHtml) {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"/><title>%s</title>
                <meta name="viewport" content="width=device-width, initial-scale=1"/>
                %s
                </head><body>
                <div class="centered"><div class="card">%s</div></div>
                </body></html>
                """.formatted(title, HEAD, cardHtml);
    }

    public static String rail(String vaultCrumb) {
        return """
                <div class="brand"><em>Stone</em>Sync</div>
                <a href="/dashboard">Your vaults</a>
                %s
                """.formatted(vaultCrumb == null ? "" : vaultCrumb);
    }
}
