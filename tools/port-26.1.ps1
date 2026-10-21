# Copies the 1.21.11 sources into mc-26.1 and applies the API renames needed for Minecraft 26.1.
# Usage (from the project root):  powershell -ExecutionPolicy Bypass -File tools\port-26.1.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root 'mc-26.1\src'
$utf8 = New-Object System.Text.UTF8Encoding($false)

if (Test-Path $target) { Remove-Item -Recurse -Force $target }
Copy-Item (Join-Path $root 'src') $target -Recurse

$javaRenames = [ordered]@{
    'net.minecraft.client.gui.GuiGraphics;'                     = 'net.minecraft.client.gui.GuiGraphicsExtractor;'
    'GuiGraphics '                                              = 'GuiGraphicsExtractor '
    '.drawString('                                              = '.text('
    'public void render(GuiGraphicsExtractor g, int mx, int my, float pt)' = 'public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt)'
    'super.render(g, mx, my, pt)'                               = 'super.extractRenderState(g, mx, my, pt)'
    'public void renderBackground(GuiGraphicsExtractor g'       = 'public void extractBackground(GuiGraphicsExtractor g'
    'renderBlurredBackground(g)'                                = 'extractBlurredBackground(g)'
    'net.minecraft.client.renderer.state.CameraRenderState;'    = 'net.minecraft.client.renderer.state.level.CameraRenderState;'
    'Lnet/minecraft/client/renderer/state/CameraRenderState;'   = 'Lnet/minecraft/client/renderer/state/level/CameraRenderState;'
    'import net.minecraft.client.renderer.LightTexture;'        = 'import net.minecraft.util.LightCoordsUtil;'
    'LightTexture.FULL_BRIGHT'                                  = 'LightCoordsUtil.FULL_BRIGHT'
    'import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;' = 'import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;'
    'KeyBindingHelper.registerKeyBinding('                      = 'KeyMappingHelper.registerKeyMapping('
    'PayloadTypeRegistry.playC2S()'                             = 'PayloadTypeRegistry.serverboundPlay()'
    'PayloadTypeRegistry.playS2C()'                             = 'PayloadTypeRegistry.clientboundPlay()'
    'method = "submitNameTag('                                  = 'method = "submitNameDisplay('
    'renderEntityInInventoryFollowsMouse('                     = 'extractEntityInInventoryFollowsMouse('
    'import net.minecraft.client.gui.components.PlayerFaceRenderer;' = 'import net.minecraft.client.gui.components.PlayerFaceExtractor;'
    'PlayerFaceRenderer.draw('                                  = 'PlayerFaceExtractor.extractRenderState('
    '.getChat().addMessage('                                    = '.getChat().addClientSystemMessage('
    'public void render(GuiGraphicsExtractor g, Font font, long visibleMs)' = 'public void extractRenderState(GuiGraphicsExtractor g, Font font, long visibleMs)'
}

Get-ChildItem $target -Recurse -Filter *.java | ForEach-Object {
    $text = [IO.File]::ReadAllText($_.FullName, $utf8)
    $orig = $text
    foreach ($k in $javaRenames.Keys) { $text = $text.Replace($k, $javaRenames[$k]) }
    # CardBaker draws with Java2D, whose drawString must stay.
    if ($_.Name -eq 'CardBaker.java') { $text = $text.Replace('g.text(', 'g.drawString(') }
    if ($text -ne $orig) { [IO.File]::WriteAllText($_.FullName, $text, $utf8) }
}

$modJson = Join-Path $target 'main\resources\fabric.mod.json'
$text = [IO.File]::ReadAllText($modJson, $utf8).Replace('"minecraft": "~1.21.11"', '"minecraft": "~26.1"').Replace('"java": ">=21"', '"java": ">=25"')
[IO.File]::WriteAllText($modJson, $text, $utf8)

$mixins = Join-Path $target 'main\resources\spotifyglass.mixins.json'
$text = [IO.File]::ReadAllText($mixins, $utf8).Replace('JAVA_21', 'JAVA_25')
[IO.File]::WriteAllText($mixins, $text, $utf8)

Write-Output "mc-26.1 sources updated."
