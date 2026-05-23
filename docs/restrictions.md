# Restrictions

Restrictions can be configured in this plugin's `config.yaml` You may also use `/restrictions disallow <moduleSlug>` to add a module to the disallow list. Use `/restrictions allow <moduleSlug>` to remove it again and allow players to use that module.

**Most client TuffClient versions require all module slugs to be __all lowercase__.**

## Exploit Discovered - it is recommended to always restrict these
`clientbrand` - Old version of `clientbrander` - This version still displayed the brand players who were invisible.

## Possible PVP or Progression Advantage
`quickelytra` - Automatically use elytra when using a firework
`fastcrystals` - Instantly removes end crystals client-side when attacked (allows faster spam - counteracts bad ping)
`anchoroptimizer` - Predicts Respawn Anchor explosions client-side.
`hotbaroptimizer` - Instantly syncs hotbar slot selection to the server
`hotbar_switcher` - Hotbar Switcher switch rows with (key) + (num).
`fullbright` - Maximizes in-game brightness.
`tnt` - Adds a timer above TNT to show the amount of time before explosion
`antipickup` - Auto drops blacklisted items
`zoom` - Zoom in by hitting the keybind you set in Controls (Default to Z)
`glowingores` - Makes ores glow
`rangecrosshair` - Turns crosshair red if an entity is in hitting range
`betterhitboxes` - Turns hitbox a different color if the entity can be hit
`minimap` - Enabled a minimap when you are moving

## Visual cleanup
`cpvpmode` - Removes fire effects, smoke, and crystal explosion particles
`noexplosionparticles` - Removes explosion particles for better visibility
`norain` - Disables rain particles
`noglint` - Disables enchant glint for items
`nodynamicfov` - Removes Dynamic FOV
`smalltools` - Renders held tools smaller in first-person.
`noeffect` - Removes particles from rendering
`nohurtcam` - Removes the Hurt Shake
`nodeathanimation` - Removes the death animation tilt on entities
`chatclear` - Makes chat background to be clear
`nobackgroundtint` - Disables background tint in-game
`lowonfire` - Adjust the size of the fire overlay in your GUI!

## Gameplay Practice/Information
`wtaptrainer` - Practice W-tapping timing without modifying combat
`pvptracker` - Tracks your PVP stats.
`cps` - Displays your clicks per second.
`speed` - Shows your current movement speed
`reach` - Shows how many blocks away whatever entity you hit is
`combocounter` - Show current combo count
`shieldstatus` - Colors shields based on their state
`totem_counter` - Tells you how many totems you have in your inventory
`fps` - Displays your FPS
`armorhud` - Shows the Armor HUD, and warns you if it has low durability.
`speedrun_timer` - Lets you start a timer as soon as you enter the world

## Utility
`sprinttoggle` - Toggle your Sprinting
`shifttoggle` - Toggle your Sneaking
`autologin` - Automatically logs you in
`tabcomplete` - Enable the modern chat completion UI.
`worldeditcui` - Shows WorldEdit selections in-game.
`autogg` - Sends 'gg' whenever the game ends
`fancy_hover_block` - Changes the color of the block hovering to a gradient

## Other Information
`clientbrander` - Shows what client other Eaglercraft players are using above their name
`appleskin` - The Appleskin Mod!
`compass` - Shows a compass on the HUD.
`inventoryHUD` - Displays your inventory items on screen
`keystrokes` - Shows Keystrokes
`WAILA` - Displays what block you are looking at.
`potions` - Shows active effects
`shulker` - Shows shulker box contents
`chatheads` - Renders player heads next to their chat messages

## Other Modules (Not recommended to disallow)

### In-Game Customization
`crosshair` - Customize your crosshair!
`waveycapes` - Capes, but wavey
`mace3d` - Renders the mace item as a 3D model
`braysbow` - Custom bow and arrow models

### Backport
**y0, ViaBlocks, and ViaEntities have separate configuration within the plugin**
`viaviewer` - Allows viewing new items on servers with newer versions (100% client-side)
`viablocks` - Allows viewing new placed blocks on servers with newer versions
`y0` - Allows below y0 support - disabling this ignores y0 packets
`viaviewerentity` - Allows viewing new entities on servers with newer versions

### Performance
`vanillafix_chunk` - Speeds up chunk unloading (possible issues, may require rejoining)
`ldm` - Reduces lag in areas that are not needed. Prevents Memory Leaks on low-end devices

### Non-game GUIs
`listlayout` - Swap between list and grid layouts
`teto_mode` - Joke theme lmao - Kasane Teto
`debug` - Debug mode
`sodiumUI` - Switches to sodium video settings UI.
`minecraftgui` - Toggles between Tuff UI and Minecraft UI
`movingBG` - Toggles moving the main menu background based on mouse movement

##

### Old modules
`smalltotems` - Renders held totems smaller in first-person
`durability` - Shows your Armor's Durability on the hotbar
`colorful_containers` - Makes Container GUIs colorful
`enhanced_hotbar` - Makes your healthbar icons like Hunger and Health prettier
`healthbar` - Displays other entities' health in a healthbar above their name.
`streamer` - Hides mentions of your XYZ coordinates
`widgets` - Modifies hotbar/buttons/minecraft GUI related
`fastmath` - Speeds up FPS by improving math functions
