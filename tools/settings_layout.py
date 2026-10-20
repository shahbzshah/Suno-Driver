"""Layout surgery for v1.5:
1. Lift the NVIDIA key card out of the Write screen (ids preserved).
2. Add a Settings button beside Refresh in the tab row.
3. Build the Settings screen: Back row, APP INFO, API (the moved card),
   UPDATES, ABOUT, HELP, DEVELOPER — same Stitch card language.
No copy anywhere claims the app was coded with AI (Megah's rule).
"""
import pathlib

p = pathlib.Path("/home/hatch/workspace/suno-driver-poc/app/src/main/res/layout/activity_main.xml")
xml = p.read_text()

# --- 1. Extract the key card block ---
start_marker = "                <!-- NVIDIA key card -->"
end_marker = "                <!-- Song idea card -->"
s = xml.index(start_marker)
e = xml.index(end_marker)
key_card = xml[s:e]
assert 'android:id="@+id/etApiKey"' in key_card and 'tvAuthState' in key_card
xml = xml[:s] + xml[e:]
# Give the moved card a top margin inside Settings (it was first on Write).
key_card = key_card.replace(
    'android:layout_height="wrap_content"\n                    android:background="@drawable/bg_card"',
    'android:layout_height="wrap_content"\n                    android:layout_marginTop="14dp"\n                    android:background="@drawable/bg_card"',
    1,
)
key_card = key_card.replace("<!-- NVIDIA key card -->", "<!-- API card (moved from Write) -->")

# --- 2. Settings button after the Refresh button ---
refresh_anchor = '''            android:text="↻"
            android:textColor="@color/sd_amber_light"
            android:textSize="21sp" />
'''
assert refresh_anchor in xml
settings_btn = refresh_anchor + '''
        <ImageButton
            android:id="@+id/btnSettings"
            android:layout_width="44dp"
            android:layout_height="44dp"
            android:layout_marginStart="10dp"
            android:background="@drawable/bg_circle_btn"
            android:contentDescription="Settings"
            android:padding="11dp"
            android:src="@drawable/ic_settings"
            android:tint="@color/sd_amber_light" />
'''
xml = xml.replace(refresh_anchor, settings_btn, 1)

# --- helpers for the settings cards ---
def card_header(title: str) -> str:
    return f'''                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:gravity="center_vertical"
                        android:orientation="horizontal">

                        <View
                            android:layout_width="8dp"
                            android:layout_height="8dp"
                            android:background="@drawable/dot_amber" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginStart="8dp"
                            android:fontFamily="@font/mono"
                            android:letterSpacing="0.12"
                            android:text="{title}"
                            android:textColor="@color/sd_text"
                            android:textSize="11sp" />
                    </LinearLayout>
'''

def info_row(label: str, value: str, view_id: str | None = None, first: bool = False) -> str:
    id_attr = f'\n                            android:id="@+id/{view_id}"' if view_id else ""
    mt = "8dp" if first else "10dp"
    return f'''                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="{mt}"
                        android:orientation="horizontal">

                        <TextView
                            android:layout_width="92dp"
                            android:layout_height="wrap_content"
                            android:fontFamily="@font/mono"
                            android:text="{label}"
                            android:textColor="@color/sd_text_faint"
                            android:textSize="11sp" />

                        <TextView{id_attr}
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:fontFamily="@font/body"
                            android:text="{value}"
                            android:textColor="@color/sd_text"
                            android:textSize="13sp" />
                    </LinearLayout>
'''

def body_text(text: str, first: bool = False) -> str:
    mt = "10dp" if first else "8dp"
    return f'''                    <TextView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="{mt}"
                        android:fontFamily="@font/body"
                        android:lineSpacingMultiplier="1.3"
                        android:text="{text}"
                        android:textColor="@color/sd_text_dim"
                        android:textSize="13sp" />
'''

def card(inner: str, first: bool = False) -> str:
    mt = "" if first else '\n                    android:layout_marginTop="14dp"'
    return f'''                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"{mt}
                    android:background="@drawable/bg_card"
                    android:orientation="vertical"
                    android:padding="14dp">

{inner}                </LinearLayout>
'''

app_info = card(
    card_header("APP INFO")
    + info_row("NAME", "Suno Driver", first=True)
    + info_row("VERSION", "—", "tvInfoVersion")
    + info_row("PACKAGE", "com.megah.sunopoc")
    + info_row("RELEASES", "github.com/shahbzshah/Suno-Driver"),
    first=True,
)

updates = card(
    card_header("UPDATES")
    + body_text(
        "This app always runs the latest version. It checks GitHub on every "
        "launch and updates itself when a new release is out.",
        first=True,
    )
    + info_row("INSTALLED", "—", "tvUpdateCurrent")
    + '''                    <TextView
                        android:id="@+id/tvUpdateStatus"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="10dp"
                        android:fontFamily="@font/body"
                        android:text="Not checked yet this session."
                        android:textColor="@color/sd_text_dim"
                        android:textSize="13sp" />

                    <TextView
                        android:id="@+id/btnCheckUpdates"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:background="@drawable/bg_btn_outline"
                        android:fontFamily="@font/mono"
                        android:gravity="center"
                        android:letterSpacing="0.08"
                        android:padding="12dp"
                        android:text="CHECK FOR UPDATES"
                        android:textColor="@color/sd_amber"
                        android:textSize="12sp" />
'''
)

about = card(
    card_header("ABOUT")
    + body_text(
        "Suno Driver is the studio companion for the DeepSoundRealm and "
        "StillMind Music Official YouTube channels.",
        first=True,
    )
    + body_text(
        "Type a one-line song idea and the app prepares the Suno prompt to "
        "the channel's rulebook — hook first, natural words, each channel's "
        "own style — then fills the create page and starts the generation. "
        "Everything runs on your phone, with your own Suno account."
    )
    + body_text(
        "You stay the producer: the app starts the song, you pick the take "
        "that ships."
    )
)

help_card = card(
    card_header("HELP")
    + body_text("1.  Add your NVIDIA API key once — Settings, API section. It is saved on this phone only.", first=True)
    + body_text("2.  On the Write screen, type a one-line song idea and pick a channel.")
    + body_text("3.  Tap AI Write &amp; Create. The prompt is written, Suno's create page is filled, and generation starts.")
    + body_text("4.  The first time, log in to Suno on the Suno Page tab. Your login is remembered on this phone.")
    + body_text("5.  Listen to the takes on the Suno Page and pick your favourite.")
    + body_text("If Suno shows “Verify you are human”, open the Suno Page and tap the checkbox, then create again.")
    + body_text("If you are signed out, log in again on the Suno Page — the create form only appears when you are signed in.")
    + body_text("If something looks wrong, the Write screen keeps a session log. Tap Copy Logs and send them with your report.")
)

developer = card(
    card_header("DEVELOPER")
    + info_row("BY", "Megah", first=True)
    + info_row("GITHUB", "github.com/shahbzshah")
    + info_row("CHANNELS", "DeepSoundRealm · StillMind Music Official")
)

settings_screen = f'''        <!-- SETTINGS SCREEN -->
        <ScrollView
            android:id="@+id/svSettings"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:visibility="gone">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:paddingStart="16dp"
                android:paddingEnd="16dp"
                android:paddingBottom="16dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:gravity="center_vertical"
                    android:orientation="horizontal">

                    <TextView
                        android:id="@+id/btnBackSettings"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:fontFamily="@font/mono"
                        android:padding="8dp"
                        android:text="← BACK"
                        android:textColor="@color/sd_amber"
                        android:textSize="12sp" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="6dp"
                        android:fontFamily="@font/display"
                        android:text="Settings"
                        android:textColor="@color/sd_text"
                        android:textSize="22sp" />
                </LinearLayout>

{app_info}{key_card}{updates}{about}{help_card}{developer}            </LinearLayout>
        </ScrollView>

'''

# --- 3. Insert the settings screen inside the FrameLayout, after the WebView ---
frame_close = xml.index("</FrameLayout>")
xml = xml[:frame_close] + settings_screen + xml[frame_close:]

p.write_text(xml)
print("layout surgery done; key card moved:", "svSettings" in xml and "etApiKey" in xml)
