import Config

config :my_app, heex_brace: true, heex_tag: true
config :my_app, e_sigil: true, l_sigil: true
config :my_app, heredoc_template: true, banner: true
config :my_app, MyApp.Mailer, adapter: :local
config :my_app, MyAppWeb.Templates, layout: true
config :my_app, Crypto, enabled: true
config :my_app, template_aliased: true, template_aliased_away: true, template_imported: true, own_aliased_away: true, own_aliased: true, own_imported: true, own_parenthesised_away: true
config :my_app, "banner-text": true, title: "Home", after_escape: true
