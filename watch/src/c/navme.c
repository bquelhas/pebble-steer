#include <pebble.h>

static bool s_invert_color = false; // false = dark theme, true = light theme
static GColor s_bg_color;
static bool s_backlight_always_on = false;
static bool s_backlight_forced_on = false;

#define NAV_SCREEN_BG GColorFromRGB(0xFF, 0x4B, 0x49)  // Solid Red (#ff4b49)
#define PERSIST_KEY_FAV_COUNT 100
#define PERSIST_KEY_FAVORITES 101

static GColor prv_top_bg(void) {
#if defined(PBL_COLOR)
  return s_invert_color ? GColorWhite : s_bg_color;
#else
  return s_invert_color ? GColorBlack : GColorWhite;
#endif
}

#if defined(PBL_COLOR)
static GColor prv_get_contrast_color(GColor bg) {
  int r = bg.r * 85;
  int g = bg.g * 85;
  int b = bg.b * 85;
  int luminance = (2126 * r + 7152 * g + 722 * b) / 10000;
  return (luminance > 130) ? GColorBlack : GColorWhite;
}
#endif

static GColor prv_top_fg_for_bg(GColor bg) {
#if defined(PBL_COLOR)
  return prv_get_contrast_color(bg);
#else
  return s_invert_color ? GColorWhite : GColorBlack;
#endif
}

static GColor prv_top_fg(void) {
  return prv_top_fg_for_bg(prv_top_bg());
}

static GColor prv_distance_fg_for_bg(GColor bg) {
#if defined(PBL_COLOR)
  if (bg.r >= 2 && bg.g <= 1 && bg.b <= 1) {
    return GColorBlack;
  }
  return prv_get_contrast_color(bg);
#else
  return s_invert_color ? GColorWhite : GColorBlack;
#endif
}


static GColor prv_resolve_bg_color(GColor raw_bg) {
#if defined(PBL_COLOR)
  return s_invert_color ? GColorWhite : raw_bg;
#else
  return s_invert_color ? GColorBlack : GColorWhite;
#endif
}

// Maneuver enum (NAV_TURN index)
typedef enum {
  DIR_ARRIVE=0, DIR_ARRIVE_LEFT, DIR_ARRIVE_RIGHT, DIR_DEPART,
  DIR_FORK_LEFT, DIR_FORK_RIGHT, DIR_GENERIC_MERGE,
  DIR_GENERIC_ROUNDABOUT_LEFT, DIR_GENERIC_ROUNDABOUT_RIGHT,
  DIR_LEFT, DIR_RIGHT, DIR_RAMP_LEFT, DIR_RAMP_RIGHT,
  DIR_ROUNDABOUT_1_LEFT, DIR_ROUNDABOUT_2_LEFT, DIR_ROUNDABOUT_3_LEFT, DIR_ROUNDABOUT_4_LEFT,
  DIR_ROUNDABOUT_5_LEFT, DIR_ROUNDABOUT_6_LEFT, DIR_ROUNDABOUT_7_LEFT, DIR_ROUNDABOUT_8_LEFT,
  DIR_ROUNDABOUT_1_RIGHT, DIR_ROUNDABOUT_2_RIGHT, DIR_ROUNDABOUT_3_RIGHT, DIR_ROUNDABOUT_4_RIGHT,
  DIR_ROUNDABOUT_5_RIGHT, DIR_ROUNDABOUT_6_RIGHT, DIR_ROUNDABOUT_7_RIGHT, DIR_ROUNDABOUT_8_RIGHT,
  DIR_ROUNDABOUT_EXIT_LEFT, DIR_ROUNDABOUT_EXIT_RIGHT,
  DIR_SHARP_LEFT, DIR_SHARP_RIGHT, DIR_SLIGHT_LEFT, DIR_SLIGHT_RIGHT,
  DIR_STRAIGHT, DIR_UTURN_LEFT, DIR_UTURN_RIGHT,
  DIR_MERGE_LEFT, DIR_MERGE_RIGHT, DIR_CONTINUE,
  DIR_COUNT
} DirectionType;

// UI Elements
static Window *s_window;
static Layer *s_panel_layer;
static Layer *s_status_bar_layer;
static Layer *s_speed_sign_layer;

// State Variables
static char s_distance_text[32] = "";
static char s_street_text[128] = "Waiting for signal...";
static char s_eta_text[16] = "ETA: --:--";
static char s_gps_text[32] = "GPS: ---";
static int s_maneuver_index = -1;  // -1 means no active maneuver, show chevron
static bool s_vibe_on_turn = true;
static bool s_speed_alert_active = false;
static uint8_t s_speed_limit = 0;
static int s_current_speed = -1;   // current speed in km/h from the phone GPS; -1 = unknown
// Speedometer display placement is still TBD (Bruno designs the layout). The value is
// plumbed + stored now; flip this to 1 once the on-watch layout is decided.
#define STEER_SHOW_SPEEDOMETER 1

// --- Localization ---------------------------------------------------------
// English is the default UI language; Portuguese is selected automatically when
// the watch's system language is Portuguese. i18n_get_system_locale() returns a
// string like "en_US" or "pt_PT".
static bool prv_lang_is_pt(void) {
  const char *loc = i18n_get_system_locale();
  return loc && loc[0] == 'p' && loc[1] == 't';
}
// Pick between an English and a Portuguese literal based on the system locale.
static const char *prv_tr(const char *en, const char *pt) {
  return prv_lang_is_pt() ? pt : en;
}
// Fill s_street_text with the localized "waiting for signal" placeholder.
static void prv_set_waiting_text(void) {
  snprintf(s_street_text, sizeof(s_street_text), "%s",
           prv_tr("Waiting for signal...", "Aguardando sinal..."));
}

typedef struct {
  char name[32];
  bool active;
  uint8_t icon;
} FavoriteDestination;
static FavoriteDestination s_favorites[5];
static uint8_t s_fav_count = 0;

static Window *s_favorites_window = NULL;
static MenuLayer *s_favorites_menu_layer = NULL;

static Window *s_mode_window = NULL;
static MenuLayer *s_mode_menu_layer = NULL;
static uint8_t s_pending_fav_index = 0;

static const uint32_t s_pdc_resource_ids[] = {
  RESOURCE_ID_PDC_ICON_0, RESOURCE_ID_PDC_ICON_1, RESOURCE_ID_PDC_ICON_2, RESOURCE_ID_PDC_ICON_3,
  RESOURCE_ID_PDC_ICON_4, RESOURCE_ID_PDC_ICON_5, RESOURCE_ID_PDC_ICON_6, RESOURCE_ID_PDC_ICON_7,
  RESOURCE_ID_PDC_ICON_8, RESOURCE_ID_PDC_ICON_9, RESOURCE_ID_PDC_ICON_10, RESOURCE_ID_PDC_ICON_11,
  RESOURCE_ID_PDC_ICON_12, RESOURCE_ID_PDC_ICON_13, RESOURCE_ID_PDC_ICON_14, RESOURCE_ID_PDC_ICON_15,
  RESOURCE_ID_PDC_ICON_16, RESOURCE_ID_PDC_ICON_17, RESOURCE_ID_PDC_ICON_18, RESOURCE_ID_PDC_ICON_19,
  RESOURCE_ID_PDC_ICON_20, RESOURCE_ID_PDC_ICON_21, RESOURCE_ID_PDC_ICON_22, RESOURCE_ID_PDC_ICON_23,
  RESOURCE_ID_PDC_ICON_24, RESOURCE_ID_PDC_ICON_25, RESOURCE_ID_PDC_ICON_26, RESOURCE_ID_PDC_ICON_27,
  RESOURCE_ID_PDC_ICON_28, RESOURCE_ID_PDC_ICON_29, RESOURCE_ID_PDC_ICON_30, RESOURCE_ID_PDC_ICON_31,
  RESOURCE_ID_PDC_ICON_32, RESOURCE_ID_PDC_ICON_33, RESOURCE_ID_PDC_ICON_34, RESOURCE_ID_PDC_ICON_35,
  RESOURCE_ID_PDC_ICON_36, RESOURCE_ID_PDC_ICON_37, RESOURCE_ID_PDC_ICON_38, RESOURCE_ID_PDC_ICON_39,
  RESOURCE_ID_PDC_ICON_40
};
#define USE_CARDS_TRANSITION 1

#if defined(PBL_PLATFORM_APLITE)
#define Steer_Has_Transitions 0
#else
#define Steer_Has_Transitions 1
#endif

#if Steer_Has_Transitions
static void prv_icon_apply_morph(GDrawCommandImage *image, int32_t morph_pct);
#if !USE_CARDS_TRANSITION
static void prv_icon_apply_scale(GDrawCommandImage *image, int32_t scale_pct, int32_t visible_pct,
                                 int32_t factor_x, int32_t factor_y);
#endif
#endif
static void prv_icon_scale_once(GDrawCommandImage *image);

static GDrawCommandImage *s_active_pdc_image = NULL;
// Steer branding: the car-rental PDC shown on the "waiting for signal" screen
// (s_maneuver_index < 0). Loaded + content-fitted once at window load.
static GDrawCommandImage *s_wait_pdc_image = NULL;

#if defined(PBL_PLATFORM_APLITE)
static uint8_t s_forwarded_icon_bytes[1];
#else
static uint8_t s_forwarded_icon_bytes[384];
#endif
static bool s_has_forwarded_icon = false;
static GBitmap *s_forwarded_icon = NULL;
static bool s_forwarded_icon_dirty = false;

typedef enum {
  ANIM_STATE_IDLE,
  ANIM_STATE_TRANSITIONING
} AnimState;

typedef enum {
  SLIDE_DIR_RIGHT_TO_LEFT,
  SLIDE_DIR_LEFT_TO_RIGHT,
  SLIDE_DIR_BOTTOM_TO_TOP
} SlideDirection;

// Number of frames in the moook transition timeline (drives slide + squash/stretch).
#define MOOOK_FRAMES 10

// Auto-close the app after this long with no inbound nav messages, so it doesn't sit on
// the watch forever once navigation ends. Rearmed on every AppMessage (and at startup);
// during active nav the frequent distance updates keep resetting it, so it only fires
// once the phone goes quiet (e.g. ~2 min after NAV_CANCEL / the last update).
#define IDLE_TIMEOUT_MS (2 * 60 * 1000)

static AnimState s_anim_state = ANIM_STATE_IDLE;
static AppTimer *s_anim_timer = NULL;
static AppTimer *s_idle_timer = NULL;
static int s_anim_pct = 100;
static int s_anim_prev_maneuver = -2;
#if Steer_Has_Transitions
// Contextual slide direction of the current transition (set per maneuver in prv_update_ui).
static SlideDirection s_anim_slide_dir = SLIDE_DIR_RIGHT_TO_LEFT;
#endif
static bool s_last_rendered_has_forwarded = false;
static char s_last_rendered_street[128] = "";
static char s_last_rendered_distance[16] = "";
static GColor s_last_rendered_bg_color;

#if Steer_Has_Transitions
// Variables for transition tracking
static GDrawCommandImage *s_prev_pdc_image = NULL;
#if defined(PBL_PLATFORM_APLITE)
static uint8_t s_prev_forwarded_icon_bytes[1];
#else
static uint8_t s_prev_forwarded_icon_bytes[384];
#endif
static int s_prev_maneuver_index = -2;
static bool s_prev_has_forwarded = false;
static char s_prev_distance_text[16] = "";
static char s_prev_street_text[128] = "";
static GColor s_prev_bg_color;
#endif

#if defined(PBL_COLOR)
// ---- Per-digit squash animation (Color platforms only) ------------------------------
// On a same-maneuver distance countdown (e.g. 300 -> 250 -> 200), every digit that
// changes collapses vertically to a flat line at its fixed base while the replacement
// digit grows from that same base line — "the new number being born pushes the old one
// out" (Bruno). The base stays put, only the top moves. The LECO font look and position
// are preserved exactly: unchanged columns and the final settled frame are drawn with
// the real system font; only the in-flight changed columns use captured glyph masks.
//
// Mechanism: the 10 LECO digit glyphs are captured once from the framebuffer into 1-bpp
// masks (the only way to rasterise a system font on Pebble), then squashed copies are
// blitted as horizontal runs over the changed columns during the ~200 ms morph.
#define DG_W 44                         // capture/blit cell width per digit (px)
#define DG_H 52                         // capture/blit cell height per digit (px)
#define DG_ROWBYTES ((DG_W + 7) / 8)    // 6
static uint8_t s_dg_mask[10][DG_H * DG_ROWBYTES];
static int s_dg_adv = 0;                // monospace digit advance (px)
static int s_dg_ink_top = 0;           // common ink top row across all digits
static int s_dg_ink_bot = 0;           // common ink bottom row (the fixed base)
static bool s_dg_ready = false;         // glyph masks captured?
static AppTimer *s_dg_timer = NULL;
static bool s_dg_active = false;        // a digit morph is in flight
static int s_dg_pct = 0;                // 0..100 morph progress
static char s_dg_old[16] = "";          // previous numeric string (digits only)
static char s_dg_new[16] = "";          // current numeric string (digits only)
#endif

// Forward declaration of prv_draw_icon
static void prv_draw_icon(GContext *ctx, GRect bounds, int offset_x, bool has_fwd, const uint8_t *fwd_icon_bytes, GDrawCommandImage *pdc, int index, int scale_pct);

static void prv_update_backlight(void) {
  bool should_enable = s_backlight_always_on;
  if (!should_enable && s_maneuver_index >= 0) {
    time_t temp = time(NULL);
    struct tm *tick_time = localtime(&temp);
    int hour = tick_time->tm_hour;
    // Define "night" as 20:00 (8 PM) to 07:00 (7 AM)
    if (hour >= 20 || hour < 7) {
      should_enable = true;
    }
  }

  if (should_enable) {
    if (!s_backlight_forced_on) {
      light_enable(true);
      s_backlight_forced_on = true;
    }
#if defined(PBL_PLATFORM_EMERY)
    light_set_color(GColorRed);
#endif
  } else {
    if (s_backlight_forced_on) {
      light_enable(false);
      s_backlight_forced_on = false;
#if defined(PBL_PLATFORM_EMERY)
      light_set_color(GColorWhite);
#endif
    }
  }
}

// Procedural Drawing Helpers
static void prv_draw_filled_triangle(GContext *ctx, GPoint p1, GPoint p2, GPoint p3) {
  GPathInfo info = {
    .num_points = 3,
    .points = (GPoint[]) { p1, p2, p3 }
  };
  GPath *path = gpath_create(&info);
  gpath_draw_filled(ctx, path);
  gpath_destroy(path);
}

static void prv_draw_procedural_arrow(GContext *ctx, GRect bounds, int index) {
  int w = bounds.size.w;
  int h = bounds.size.h;
  int ox = bounds.origin.x;
  int oy = bounds.origin.y;
  
  GColor color = prv_top_fg();
  graphics_context_set_stroke_color(ctx, color);
  graphics_context_set_fill_color(ctx, color);
  
  int sw = w > 60 ? 5 : 4; // stroke width
  graphics_context_set_stroke_width(ctx, sw);
  
  if (index < 0 || index >= DIR_COUNT) {
    // Draw Chevron (idle / search state)
    int cy = oy + h / 2;
    int cx = ox + w / 2;
    int size = w > 60 ? 15 : 12;
    graphics_draw_line(ctx, GPoint(cx - size, cy + size/2), GPoint(cx, cy - size/2));
    graphics_draw_line(ctx, GPoint(cx, cy - size/2), GPoint(cx + size, cy + size/2));
    
    graphics_draw_line(ctx, GPoint(cx - size, cy + size/2 + size), GPoint(cx, cy - size/2 + size));
    graphics_draw_line(ctx, GPoint(cx, cy - size/2 + size), GPoint(cx + size, cy + size/2 + size));
    return;
  }
  
  switch (index) {
    case DIR_STRAIGHT:
    case DIR_CONTINUE:
    default: {
      // Straight Arrow
      graphics_draw_line(ctx, GPoint(ox + w/2, oy + h - sw), GPoint(ox + w/2, oy + sw + 8));
      prv_draw_filled_triangle(ctx, 
        GPoint(ox + w/2, oy + sw), 
        GPoint(ox + w/2 - sw*2 - 2, oy + sw + sw*2 + 2), 
        GPoint(ox + w/2 + sw*2 + 2, oy + sw + sw*2 + 2)
      );
      break;
    }
    case DIR_LEFT: {
      // Left Turn
      graphics_draw_line(ctx, GPoint(ox + w/2 + sw, oy + h - sw), GPoint(ox + w/2 + sw, oy + h/2));
      graphics_draw_line(ctx, GPoint(ox + w/2 + sw, oy + h/2), GPoint(ox + sw + 8, oy + h/2));
      prv_draw_filled_triangle(ctx, 
        GPoint(ox + sw, oy + h/2), 
        GPoint(ox + sw + sw*2 + 2, oy + h/2 - sw*2 - 2), 
        GPoint(ox + sw + sw*2 + 2, oy + h/2 + sw*2 + 2)
      );
      break;
    }
    case DIR_RIGHT: {
      // Right Turn
      graphics_draw_line(ctx, GPoint(ox + w/2 - sw, oy + h - sw), GPoint(ox + w/2 - sw, oy + h/2));
      graphics_draw_line(ctx, GPoint(ox + w/2 - sw, oy + h/2), GPoint(ox + w - sw - 8, oy + h/2));
      prv_draw_filled_triangle(ctx, 
        GPoint(ox + w - sw, oy + h/2), 
        GPoint(ox + w - sw - sw*2 - 2, oy + h/2 - sw*2 - 2), 
        GPoint(ox + w - sw - sw*2 - 2, oy + h/2 + sw*2 + 2)
      );
      break;
    }
    case DIR_SLIGHT_LEFT: {
      // Slight Left (45 degrees up-left)
      graphics_draw_line(ctx, GPoint(ox + w/2 + sw, oy + h - sw), GPoint(ox + w/2 + sw, oy + h/2 + sw));
      graphics_draw_line(ctx, GPoint(ox + w/2 + sw, oy + h/2 + sw), GPoint(ox + sw + 12, oy + sw + 12));
      int tip_x = ox + sw + 8;
      int tip_y = oy + sw + 8;
      prv_draw_filled_triangle(ctx, 
        GPoint(tip_x, tip_y),
        GPoint(tip_x + sw*2 + 4, tip_y),
        GPoint(tip_x, tip_y + sw*2 + 4)
      );
      break;
    }
    case DIR_SLIGHT_RIGHT: {
      // Slight Right (45 degrees up-right)
      graphics_draw_line(ctx, GPoint(ox + w/2 - sw, oy + h - sw), GPoint(ox + w/2 - sw, oy + h/2 + sw));
      graphics_draw_line(ctx, GPoint(ox + w/2 - sw, oy + h/2 + sw), GPoint(ox + w - sw - 12, oy + sw + 12));
      int tip_x = ox + w - sw - 8;
      int tip_y = oy + sw + 8;
      prv_draw_filled_triangle(ctx, 
        GPoint(tip_x, tip_y),
        GPoint(tip_x - sw*2 - 4, tip_y),
        GPoint(tip_x, tip_y + sw*2 + 4)
      );
      break;
    }
    case DIR_SHARP_LEFT: {
      // Sharp Left (135 degrees down-left)
      graphics_draw_line(ctx, GPoint(ox + w/2 + sw*2, oy + h - sw), GPoint(ox + w/2 + sw*2, oy + h/3));
      graphics_draw_line(ctx, GPoint(ox + w/2 + sw*2, oy + h/3), GPoint(ox + sw + 12, oy + h - sw - 16));
      int tip_x = ox + sw + 8;
      int tip_y = oy + h - sw - 12;
      prv_draw_filled_triangle(ctx, 
        GPoint(tip_x, tip_y),
        GPoint(tip_x, tip_y - sw*2 - 4),
        GPoint(tip_x + sw*2 + 4, tip_y)
      );
      break;
    }
    case DIR_SHARP_RIGHT: {
      // Sharp Right (135 degrees down-right)
      graphics_draw_line(ctx, GPoint(ox + w/2 - sw*2, oy + h - sw), GPoint(ox + w/2 - sw*2, oy + h/3));
      graphics_draw_line(ctx, GPoint(ox + w/2 - sw*2, oy + h/3), GPoint(ox + w - sw - 12, oy + h - sw - 16));
      int tip_x = ox + w - sw - 8;
      int tip_y = oy + h - sw - 12;
      prv_draw_filled_triangle(ctx, 
        GPoint(tip_x, tip_y),
        GPoint(tip_x, tip_y - sw*2 - 4),
        GPoint(tip_x - sw*2 - 4, tip_y)
      );
      break;
    }
    case DIR_UTURN_LEFT: {
      // U-Turn Left
      int top = sw + 8;
      graphics_draw_line(ctx, GPoint(ox + w/2 + sw*2, oy + h - sw), GPoint(ox + w/2 + sw*2, oy + top + sw*2));
      graphics_draw_line(ctx, GPoint(ox + w/2 + sw*2, oy + top + sw*2), GPoint(ox + w/2 - sw*2, oy + top + sw*2));
      graphics_draw_line(ctx, GPoint(ox + w/2 - sw*2, oy + top + sw*2), GPoint(ox + w/2 - sw*2, oy + h - sw - 8));
      prv_draw_filled_triangle(ctx, 
        GPoint(ox + w/2 - sw*2, oy + h - sw), 
        GPoint(ox + w/2 - sw*2 - sw*2, oy + h - sw - sw*2 - 2), 
        GPoint(ox + w/2 - sw*2 + sw*2, oy + h - sw - sw*2 - 2)
      );
      break;
    }
    case DIR_UTURN_RIGHT: {
      // U-Turn Right
      int top = sw + 8;
      graphics_draw_line(ctx, GPoint(ox + w/2 - sw*2, oy + h - sw), GPoint(ox + w/2 - sw*2, oy + top + sw*2));
      graphics_draw_line(ctx, GPoint(ox + w/2 - sw*2, oy + top + sw*2), GPoint(ox + w/2 + sw*2, oy + top + sw*2));
      graphics_draw_line(ctx, GPoint(ox + w/2 + sw*2, oy + top + sw*2), GPoint(ox + w/2 + sw*2, oy + h - sw - 8));
      prv_draw_filled_triangle(ctx, 
        GPoint(ox + w/2 + sw*2, oy + h - sw), 
        GPoint(ox + w/2 + sw*2 - sw*2, oy + h - sw - sw*2 - 2), 
        GPoint(ox + w/2 + sw*2 + sw*2, oy + h - sw - sw*2 - 2)
      );
      break;
    }
    case DIR_FORK_LEFT:
    case DIR_RAMP_LEFT: {
      // Fork / Ramp Left
      graphics_draw_line(ctx, GPoint(ox + w/2, oy + h - sw), GPoint(ox + w/2, oy + sw + 8));
      prv_draw_filled_triangle(ctx, 
        GPoint(ox + w/2, oy + sw), 
        GPoint(ox + w/2 - sw - 2, oy + sw + sw*2 + 2), 
        GPoint(ox + w/2 + sw + 2, oy + sw + sw*2 + 2)
      );
      graphics_draw_line(ctx, GPoint(ox + w/2, oy + h/2 + sw*2), GPoint(ox + sw + 12, oy + h/2 - sw));
      int tip_x = ox + sw + 8;
      int tip_y = oy + h/2 - sw - 4;
      prv_draw_filled_triangle(ctx, 
        GPoint(tip_x, tip_y),
        GPoint(tip_x + sw*2 + 2, tip_y),
        GPoint(tip_x, tip_y + sw*2 + 2)
      );
      break;
    }
    case DIR_FORK_RIGHT:
    case DIR_RAMP_RIGHT: {
      // Fork / Ramp Right
      graphics_draw_line(ctx, GPoint(ox + w/2, oy + h - sw), GPoint(ox + w/2, oy + sw + 8));
      prv_draw_filled_triangle(ctx, 
        GPoint(ox + w/2, oy + sw), 
        GPoint(ox + w/2 - sw - 2, oy + sw + sw*2 + 2), 
        GPoint(ox + w/2 + sw + 2, oy + sw + sw*2 + 2)
      );
      graphics_draw_line(ctx, GPoint(ox + w/2, oy + h/2 + sw*2), GPoint(ox + w - sw - 12, oy + h/2 - sw));
      int tip_x = ox + w - sw - 8;
      int tip_y = oy + h/2 - sw - 4;
      prv_draw_filled_triangle(ctx, 
        GPoint(tip_x, tip_y),
        GPoint(tip_x - sw*2 - 2, tip_y),
        GPoint(tip_x, tip_y + sw*2 + 2)
      );
      break;
    }
    case DIR_GENERIC_MERGE:
    case DIR_MERGE_LEFT:
    case DIR_MERGE_RIGHT: {
      // Merge
      graphics_draw_line(ctx, GPoint(ox + w/2, oy + h/2), GPoint(ox + w/2, oy + sw + 8));
      prv_draw_filled_triangle(ctx, 
        GPoint(ox + w/2, oy + sw), 
        GPoint(ox + w/2 - sw*2 - 2, oy + sw + sw*2 + 2), 
        GPoint(ox + w/2 + sw*2 + 2, oy + sw + sw*2 + 2)
      );
      graphics_draw_line(ctx, GPoint(ox + w/2 - sw*3, oy + h - sw), GPoint(ox + w/2, oy + h/2));
      graphics_draw_line(ctx, GPoint(ox + w/2 + sw*3, oy + h - sw), GPoint(ox + w/2, oy + h/2));
      break;
    }
    case DIR_ARRIVE:
    case DIR_ARRIVE_LEFT:
    case DIR_ARRIVE_RIGHT:
    case DIR_DEPART: {
      // Arrive / Location Pin Symbol
      int radius = w > 60 ? 15 : 12;
      int cx = ox + w/2;
      int cy = oy + h/2 - radius/2;
      graphics_fill_circle(ctx, GPoint(cx, cy), radius);
      prv_draw_filled_triangle(ctx, 
        GPoint(cx, cy + radius + radius/2), 
        GPoint(cx - radius + 2, cy + radius/2), 
        GPoint(cx + radius - 2, cy + radius/2)
      );
      graphics_context_set_fill_color(ctx, prv_top_bg());
      graphics_fill_circle(ctx, GPoint(cx, cy), radius/3 + 1);
      break;
    }
    case DIR_ROUNDABOUT_1_LEFT: case DIR_ROUNDABOUT_2_LEFT: case DIR_ROUNDABOUT_3_LEFT:
    case DIR_ROUNDABOUT_4_LEFT: case DIR_ROUNDABOUT_5_LEFT: case DIR_ROUNDABOUT_6_LEFT:
    case DIR_ROUNDABOUT_7_LEFT: case DIR_ROUNDABOUT_8_LEFT: case DIR_GENERIC_ROUNDABOUT_LEFT:
    case DIR_ROUNDABOUT_EXIT_LEFT: {
      // Roundabout Left (counter-clockwise)
      int radius = w > 60 ? 18 : 14;
      int cx = ox + w/2;
      int cy = oy + h/2;
      graphics_draw_circle(ctx, GPoint(cx, cy), radius);
      graphics_draw_line(ctx, GPoint(cx, oy + h - sw), GPoint(cx, cy + radius));
      graphics_draw_line(ctx, GPoint(cx - radius, cy), GPoint(ox + sw + 8, cy));
      prv_draw_filled_triangle(ctx, 
        GPoint(ox + sw, cy), 
        GPoint(ox + sw + sw*2 + 2, cy - sw*2), 
        GPoint(ox + sw + sw*2 + 2, cy + sw*2)
      );
      break;
    }
    case DIR_ROUNDABOUT_1_RIGHT: case DIR_ROUNDABOUT_2_RIGHT: case DIR_ROUNDABOUT_3_RIGHT:
    case DIR_ROUNDABOUT_4_RIGHT: case DIR_ROUNDABOUT_5_RIGHT: case DIR_ROUNDABOUT_6_RIGHT:
    case DIR_ROUNDABOUT_7_RIGHT: case DIR_ROUNDABOUT_8_RIGHT: case DIR_GENERIC_ROUNDABOUT_RIGHT:
    case DIR_ROUNDABOUT_EXIT_RIGHT: {
      // Roundabout Right (clockwise)
      int radius = w > 60 ? 18 : 14;
      int cx = ox + w/2;
      int cy = oy + h/2;
      graphics_draw_circle(ctx, GPoint(cx, cy), radius);
      graphics_draw_line(ctx, GPoint(cx, oy + h - sw), GPoint(cx, cy + radius));
      graphics_draw_line(ctx, GPoint(cx + radius, cy), GPoint(ox + w - sw - 8, cy));
      prv_draw_filled_triangle(ctx, 
        GPoint(ox + w - sw, cy), 
        GPoint(ox + w - sw - sw*2 - 2, cy - sw*2), 
        GPoint(ox + w - sw - sw*2 - 2, cy + sw*2)
      );
      break;
    }
  }
}

// Platform Specific Sizing & Layout Mappings
#if defined(PBL_PLATFORM_CHALK)
  // Chalk: 180x180 circular screen
  #define STATUS_BAR_RECT           GRect(0, 0, 180, 25)
  #define STATUS_BAR_HEIGHT         25
  #define ICON_RELATIVE_RECT        GRect(14, 8, 50, 50)
  #define DISTANCE_RELATIVE_RECT    GRect(74, 17, 81, 32)
  #define STREET_RELATIVE_RECT      GRect(20, 74, 140, 44)
  #define ETA_RELATIVE_RECT         GRect(20, 121, 140, 16)
  #define DISTANCE_FONT    FONT_KEY_LECO_32_BOLD_NUMBERS
  #define STREET_FONT      FONT_KEY_GOTHIC_18_BOLD
#elif defined(PBL_PLATFORM_EMERY)
  // Emery: 200x228 rectangular screen
  #define STATUS_BAR_RECT           GRect(0, 0, 200, 24)
  #define STATUS_BAR_HEIGHT         24
  #define ICON_RELATIVE_RECT        GRect(8, 4, 80, 80)
  #define DISTANCE_RELATIVE_RECT    GRect(94, 19, 98, 70)
  #define STREET_RELATIVE_RECT      GRect(8, 90, 184, 108)
  #define DISTANCE_FONT    FONT_KEY_LECO_38_BOLD_NUMBERS
  #define STREET_FONT      FONT_KEY_GOTHIC_24_BOLD
#else
  // Basalt / Aplite / Diorite: 144x168 rectangular screen
  #define STATUS_BAR_RECT           GRect(0, 0, 144, 18)
  #define STATUS_BAR_HEIGHT         18
  #define ICON_RELATIVE_RECT        GRect(6, 2, 50, 50)
  #define DISTANCE_RELATIVE_RECT    GRect(60, 8, 78, 38)
  #define STREET_RELATIVE_RECT      GRect(6, 56, 132, 90)
  #define DISTANCE_FONT    FONT_KEY_LECO_32_BOLD_NUMBERS
  #define STREET_FONT      FONT_KEY_GOTHIC_18_BOLD
#endif

// Custom drawing procedures for backgrounds and status bar
static void prv_bg_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);

  // Resolve color - Entire screen uses the same background
  GColor bg_color = prv_top_bg();

  // Fill background
  graphics_context_set_fill_color(ctx, bg_color);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);
}

static void prv_status_bar_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  
  GColor text_color = prv_distance_fg_for_bg(prv_top_bg());
  graphics_context_set_text_color(ctx, text_color);
  
  // 1. Draw current clock time and ETA (GPS dots removed for cleaner layout)
  static char time_buffer[16];
  clock_copy_time_string(time_buffer, sizeof(time_buffer));
  
  GFont font = fonts_get_system_font(bounds.size.w > 144 ? FONT_KEY_GOTHIC_14_BOLD : FONT_KEY_GOTHIC_14);
  GRect time_rect;
  GRect eta_rect;
  GTextAlignment time_align = GTextAlignmentCenter;
  GTextAlignment eta_align = GTextAlignmentRight;
  const char *draw_eta = s_eta_text;

#if defined(PBL_ROUND)
  time_rect = GRect((180 - 60) / 2, (bounds.size.h - 16) / 2 - 1, 60, 16);
  time_align = GTextAlignmentCenter;
  draw_eta = NULL;
#else
  if (bounds.size.w <= 144) {
    time_rect = GRect(48, (bounds.size.h - 16) / 2 - 1, 36, 16);
    eta_rect = GRect(84, (bounds.size.h - 16) / 2 - 1, 58, 16);
    time_align = GTextAlignmentCenter;
    eta_align = GTextAlignmentRight;
  } else {
    time_rect = GRect((bounds.size.w - 60) / 2, (bounds.size.h - 16) / 2 - 1, 60, 16);
    eta_rect = GRect(bounds.size.w - 85, (bounds.size.h - 16) / 2 - 1, 80, 16);
    time_align = GTextAlignmentCenter;
    eta_align = GTextAlignmentRight;
  }
#endif
  
  graphics_draw_text(ctx, time_buffer, font, time_rect, GTextOverflowModeWordWrap, time_align, NULL);
  if (draw_eta) {
    graphics_draw_text(ctx, draw_eta, font, eta_rect, GTextOverflowModeWordWrap, eta_align, NULL);
  }
}



static void prv_speed_sign_update_proc(Layer *layer, GContext *ctx) {
  GRect b = layer_get_bounds(layer);
  
  // Fill the entire screen background with white (so the round sign floats on white)
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  
  GColor frame_color = PBL_IF_COLOR_ELSE(GColorDarkCandyAppleRed, GColorBlack);
  
  // Calculate center and radius (always round, does not touch edges)
  GPoint center = GPoint(b.size.w / 2, b.size.h / 2);
  uint16_t max_diameter = b.size.w < b.size.h ? b.size.w : b.size.h;
  uint16_t outer_radius = max_diameter / 2 - 6; // leaves 6px white border
  uint16_t thickness = outer_radius / 3; // proportional border thickness (doubled, growing inwards)
  if (thickness < 20) thickness = 20;   // minimum thickness (doubled)
  uint16_t inner_radius = outer_radius - thickness;
  
  // Draw circular border
  graphics_context_set_fill_color(ctx, frame_color);
  graphics_fill_circle(ctx, center, outer_radius);
  
  // Draw white core inside
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_circle(ctx, center, inner_radius);
  
  // Compute text boundary box inside the core
  int inner_w = (int)(inner_radius * 1.41);
  GRect text_box = GRect(center.x - inner_w / 2, center.y - 30, inner_w, 60);
  
  char limit_buf[8];
  snprintf(limit_buf, sizeof(limit_buf), "%d", s_speed_limit);
  
  graphics_context_set_text_color(ctx, GColorBlack);
  
  GFont chosen_font = fonts_get_system_font(FONT_KEY_LECO_20_BOLD_NUMBERS);
  const char* font_keys[] = {
    FONT_KEY_LECO_42_NUMBERS,
    FONT_KEY_LECO_38_BOLD_NUMBERS,
    FONT_KEY_LECO_36_BOLD_NUMBERS,
    FONT_KEY_LECO_32_BOLD_NUMBERS,
    FONT_KEY_LECO_28_LIGHT_NUMBERS,
    FONT_KEY_LECO_20_BOLD_NUMBERS
  };
  
  int max_allowed_w = (int)(text_box.size.w * 0.85);
  int h = 20; // default height fallback
  
  for (size_t i = 0; i < sizeof(font_keys)/sizeof(font_keys[0]); i++) {
    GFont f = fonts_get_system_font(font_keys[i]);
    GSize size = graphics_text_layout_get_content_size(limit_buf, f, text_box, 
                                                      GTextOverflowModeWordWrap, GTextAlignmentCenter);
    if (size.w <= max_allowed_w) {
      chosen_font = f;
      if (i == 0) h = 42;
      else if (i == 1) h = 38;
      else if (i == 2) h = 36;
      else if (i == 3) h = 32;
      else if (i == 4) h = 28;
      else h = 20;
      break;
    }
  }
  
  // Position the speed limit number (shifted up slightly to make room for km/h)
  GRect num_rect = GRect(center.x - inner_w / 2, center.y - h / 2 - 8, inner_w, h + 4);
  graphics_draw_text(ctx, limit_buf, chosen_font, num_rect, 
                     GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
  
  // Position and draw the "km/h" unit centered below the number
  GRect unit_rect = GRect(center.x - inner_w / 2, center.y + h / 2 - 6, inner_w, 18);
  GFont unit_font = fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD);
  graphics_draw_text(ctx, "km/h", unit_font, unit_rect, 
                     GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
}

static void prv_tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  if (s_status_bar_layer) {
    layer_mark_dirty(s_status_bar_layer);
  }
  prv_update_backlight();
}

static void prv_split_distance(const char *src, char *num, int num_sz, char *unit, int unit_sz) {
  num[0] = '\0';
  unit[0] = '\0';
  int n_idx = 0;
  int u_idx = 0;
  bool in_unit = false;
  
  for (int i = 0; src[i] != '\0'; i++) {
    char c = src[i];
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
      in_unit = true;
    }
    if (!in_unit) {
      if (n_idx < num_sz - 1) {
        num[n_idx++] = c;
      }
    } else {
      if (u_idx < unit_sz - 1) {
        unit[u_idx++] = c;
      }
    }
  }
  num[n_idx] = '\0';
  unit[u_idx] = '\0';
  
  // Trim trailing spaces in num
  while (n_idx > 0 && num[n_idx - 1] == ' ') {
    num[--n_idx] = '\0';
  }
  
  // Trim leading spaces in unit
  char *u_start = unit;
  while (*u_start == ' ') {
    u_start++;
  }
  if (u_start != unit) {
    memmove(unit, u_start, strlen(u_start) + 1);
  }
}

static void prv_draw_leco_char(GContext *ctx, char c, GPoint origin, int block_w, int block_h, GRect clip_rect) {
  uint8_t rows[5] = {0};
  switch (c) {
    case 'M': case 'm':
      rows[0] = 0b101; rows[1] = 0b111; rows[2] = 0b101; rows[3] = 0b101; rows[4] = 0b101;
      break;
    case 'K': case 'k':
      rows[0] = 0b101; rows[1] = 0b101; rows[2] = 0b110; rows[3] = 0b101; rows[4] = 0b101;
      break;
    case 'Y': case 'y':
      rows[0] = 0b101; rows[1] = 0b101; rows[2] = 0b010; rows[3] = 0b010; rows[4] = 0b010;
      break;
    case 'D': case 'd':
      rows[0] = 0b110; rows[1] = 0b101; rows[2] = 0b101; rows[3] = 0b101; rows[4] = 0b110;
      break;
    case 'I': case 'i':
      rows[0] = 0b111; rows[1] = 0b010; rows[2] = 0b010; rows[3] = 0b010; rows[4] = 0b111;
      break;
    case 'F': case 'f':
      rows[0] = 0b111; rows[1] = 0b100; rows[2] = 0b110; rows[3] = 0b100; rows[4] = 0b100;
      break;
    case 'T': case 't':
      rows[0] = 0b111; rows[1] = 0b010; rows[2] = 0b010; rows[3] = 0b010; rows[4] = 0b010;
      break;
    case 'H': case 'h':
      rows[0] = 0b101; rows[1] = 0b101; rows[2] = 0b111; rows[3] = 0b101; rows[4] = 0b101;
      break;
    case '/':
      rows[0] = 0b001; rows[1] = 0b001; rows[2] = 0b010; rows[3] = 0b100; rows[4] = 0b100;
      break;
    default:
      return;
  }

  for (int r = 0; r < 5; r++) {
    for (int col = 0; col < 3; col++) {
      if ((rows[r] & (1 << (2 - col))) != 0) {
        GRect block_rect = GRect(origin.x + col * block_w, origin.y + r * block_h, block_w, block_h);
        grect_clip(&block_rect, &clip_rect);
        if (block_rect.size.w > 0 && block_rect.size.h > 0) {
          graphics_fill_rect(ctx, block_rect, 0, GCornerNone);
        }
      }
    }
  }
}

static void prv_draw_leco_string(GContext *ctx, const char *str, GPoint origin, int block_w, int block_h, int spacing, GRect clip_rect) {
  int x = origin.x;
  for (int i = 0; str[i] != '\0'; i++) {
    if (str[i] == ' ') {
      x += 3 * block_w + spacing;
      continue;
    }
    prv_draw_leco_char(ctx, str[i], GPoint(x, origin.y), block_w, block_h, clip_rect);
    x += 3 * block_w + spacing;
  }
}


#if Steer_Has_Transitions && !USE_CARDS_TRANSITION
static int64_t prv_interpolate_moook(int32_t normalized, int64_t from, int64_t to) {
  static const int32_t frames_in[] = {0, 1, 20};
  static const int32_t frames_out[] = {4, 2, 1, 0};
  const int32_t num_frames_in = 3;
  const int32_t num_frames_mid = 3;
  const int32_t num_frames_total = 10;
  
  const int32_t direction = ((from == to) ? 0 : ((from < to) ? 1 : -1));
  if (direction == 0) {
    return from;
  }

  const int32_t direction_out = direction; // bounce_back = true
  int32_t frame_idx = ((normalized * num_frames_total + (65535 / (2 * num_frames_total))) / 65535);
  if (frame_idx < 0) frame_idx = 0;
  if (frame_idx >= num_frames_total) frame_idx = num_frames_total - 1;

  if (normalized >= 65535) {
    return to;
  } else if (frame_idx < num_frames_in) {
    return from + (direction * frames_in[frame_idx]);
  } else if (frame_idx < (num_frames_in + num_frames_mid)) {
    const int64_t shifted_normalized = normalized - (((int64_t) num_frames_in * 65535) / num_frames_total);
    const int32_t mid_normalized = ((int64_t) num_frames_total * shifted_normalized) / num_frames_mid;
    int64_t mid_from = from + (direction * frames_in[num_frames_in - 1]);
    int64_t mid_to = to + (direction_out * frames_out[0]);
    return mid_from + ((mid_normalized * (mid_to - mid_from)) / 65535);
  } else {
    return to + (direction_out * frames_out[frame_idx - (num_frames_in + num_frames_mid)]);
  }
}
#endif

static void prv_draw_distance(GContext *ctx, GRect bounds, int offset_x, const char *distance_text, GColor bg_color, GRect clip_rect) {
  GColor text_color = prv_distance_fg_for_bg(bg_color);
  graphics_context_set_text_color(ctx, text_color);
  
  char num[16];
  char unit[16];
  prv_split_distance(distance_text, num, sizeof(num), unit, sizeof(unit));
  
  if (num[0] == '\0') {
    return;
  }
  
  // Convert unit to uppercase to match mockup design
  for (int i = 0; unit[i] != '\0'; i++) {
    if (unit[i] >= 'a' && unit[i] <= 'z') {
      unit[i] = unit[i] - 'a' + 'A';
    }
  }
  
  GFont num_font = fonts_get_system_font(DISTANCE_FONT);
  
#if defined(PBL_PLATFORM_EMERY)
  // Emery (PT2): Stacked layout (number on top, unit underneath), both right-aligned
  
  // Draw number at the top
  GRect num_rect = GRect(bounds.origin.x + offset_x, bounds.origin.y, bounds.size.w, 38);
  grect_clip(&num_rect, &clip_rect);
  if (num_rect.size.w > 0 && num_rect.size.h > 0) {
    graphics_draw_text(ctx, num, num_font, num_rect, GTextOverflowModeWordWrap, GTextAlignmentRight, NULL);
  }
  
  // Draw unit underneath
  if (unit[0] != '\0') {
    graphics_context_set_fill_color(ctx, text_color);
    int len = strlen(unit);
    int block_w = 4;
    int block_h = 4;
    int spacing = 3;
    int unit_w = len * 3 * block_w + (len - 1) * spacing;
    int start_x = bounds.origin.x + offset_x + bounds.size.w - unit_w - 3; // Shift offset of 3 pixels to align with font right margin
    int start_y = bounds.origin.y + 46;
    prv_draw_leco_string(ctx, unit, GPoint(start_x, start_y), block_w, block_h, spacing, clip_rect);
  }
#else
  // Other platforms: Side-by-side single line layout, right-aligned
  int spacing = 1;
  int block_w = 2;
  int block_h = 2;
  
  int len = strlen(unit);
  int unit_w = (unit[0] != '\0') ? (len * 3 * block_w + (len - 1) * spacing) : 0;
  
  GSize num_size = graphics_text_layout_get_content_size(
    num, num_font, GRect(0, 0, bounds.size.w, bounds.size.h),
    GTextOverflowModeWordWrap, GTextAlignmentLeft
  );
  
  int total_w = num_size.w + 4 + unit_w;
  int start_x = bounds.origin.x + offset_x + bounds.size.w - total_w - 4;
  
  GRect num_rect = GRect(start_x, bounds.origin.y, num_size.w + 2, bounds.size.h);
  grect_clip(&num_rect, &clip_rect);
  if (num_rect.size.w > 0 && num_rect.size.h > 0) {
    graphics_draw_text(ctx, num, num_font, num_rect, GTextOverflowModeWordWrap, GTextAlignmentLeft, NULL);
  }
  
  if (unit[0] != '\0') {
    graphics_context_set_fill_color(ctx, text_color);
    int start_y = bounds.origin.y + 18; // Aligns nicely with the baseline of numbers
    prv_draw_leco_string(ctx, unit, GPoint(start_x + num_size.w + 4, start_y), block_w, block_h, spacing, clip_rect);
  }
#endif
}

static void prv_draw_street(GContext *ctx, GRect bounds, int offset_x, const char *street_text, GColor bg_color, GRect clip_rect) {
  GFont font = fonts_get_system_font(STREET_FONT);
  
  GTextAlignment alignment = GTextAlignmentLeft;
#if defined(PBL_ROUND)
  alignment = GTextAlignmentCenter;
#endif

  // Calculate text size to center it vertically
  GSize size = graphics_text_layout_get_content_size(
    street_text, font, GRect(0, 0, bounds.size.w, bounds.size.h),
    GTextOverflowModeWordWrap, alignment
  );
  
  int y_offset = (bounds.size.h - size.h) / 2;
  if (y_offset < 0) {
    y_offset = 0;
  }
  
  // Match the distance: black on the (red) card background, contrast elsewhere.
  graphics_context_set_text_color(ctx, prv_distance_fg_for_bg(bg_color));
  GRect draw_rect = GRect(bounds.origin.x + offset_x, bounds.origin.y + y_offset, bounds.size.w, bounds.size.h - y_offset);
  grect_clip(&draw_rect, &clip_rect);
  if (draw_rect.size.w > 0 && draw_rect.size.h > 0) {
    graphics_draw_text(ctx, street_text, font, draw_rect,
                       GTextOverflowModeWordWrap, alignment, NULL);
  }
}

#if defined(PBL_COLOR)
static bool prv_dg_all_digits(const char *s) {
  if (s[0] == '\0') return false;
  for (int i = 0; s[i] != '\0'; i++) {
    if (s[i] < '0' || s[i] > '9') return false;
  }
  return true;
}

// Lazily rasterise the 10 LECO digit glyphs into 1-bpp masks the first time a morph runs.
// We draw each digit one by one in a scratch cell centered horizontally at y=10, capture
// the framebuffer, and release it. This fits perfectly and safely on all screen widths/shapes.
static void prv_dg_capture(GContext *ctx, int screen_width) {
  GFont font = fonts_get_system_font(DISTANCE_FONT);

  // Monospace advance = width("00") - width("0").
  GSize s1 = graphics_text_layout_get_content_size(
    "0", font, GRect(0, 0, screen_width, 60), GTextOverflowModeWordWrap, GTextAlignmentLeft);
  GSize s2 = graphics_text_layout_get_content_size(
    "00", font, GRect(0, 0, screen_width, 60), GTextOverflowModeWordWrap, GTextAlignmentLeft);
  s_dg_adv = s2.w - s1.w;
  if (s_dg_adv < 8) s_dg_adv = s1.w;   // fallback if measurement looks wrong

  int gtop = DG_H, gbot = -1;
  memset(s_dg_mask, 0, sizeof(s_dg_mask));

  // Position of our scratch cell. Center it horizontally on screen, and put it at y=10.
  int cell_x = (screen_width - DG_W) / 2;
  int cell_y = 10;

  for (int d = 0; d < 10; d++) {
    // 1. Draw digit d white on black in the scratch cell
    graphics_context_set_fill_color(ctx, GColorBlack);
    graphics_fill_rect(ctx, GRect(cell_x, cell_y, DG_W, DG_H), 0, GCornerNone);
    graphics_context_set_text_color(ctx, GColorWhite);
    char str[2] = { (char)('0' + d), '\0' };
    graphics_draw_text(ctx, str, font, GRect(cell_x, cell_y, DG_W, DG_H),
                       GTextOverflowModeWordWrap, GTextAlignmentLeft, NULL);

    // 2. Capture the framebuffer to copy this digit
    GBitmap *fb = graphics_capture_frame_buffer(ctx);
    if (fb) {
      GRect fb_bounds = gbitmap_get_bounds(fb);
      for (int row = 0; row < DG_H; row++) {
        int screen_y = STATUS_BAR_HEIGHT + cell_y + row;
        if (screen_y < fb_bounds.origin.y || screen_y >= fb_bounds.origin.y + fb_bounds.size.h) {
          continue;
        }
        GBitmapDataRowInfo ri = gbitmap_get_data_row_info(fb, screen_y);
        if (ri.data == NULL) {
          continue;
        }
        for (int col = 0; col < DG_W; col++) {
          int screen_x = cell_x + col;
          if (screen_x < ri.min_x || screen_x > ri.max_x) continue;
          uint8_t px = ri.data[screen_x];                 // 8-bit GColor: aarrggbb (2 bits each)
          int lum = ((px >> 4) & 3) + ((px >> 2) & 3) + (px & 3);
          if (lum >= 5) {                            // >= ~half brightness counts as ink
            s_dg_mask[d][row * DG_ROWBYTES + (col >> 3)] |= (uint8_t)(1 << (col & 7));
            if (row < gtop) gtop = row;
            if (row > gbot) gbot = row;
          }
        }
      }
      graphics_release_frame_buffer(ctx, fb);
    }
  }

  if (gbot < 0) { gtop = 0; gbot = DG_H - 1; }     // safety: never captured ink
  s_dg_ink_top = gtop;
  s_dg_ink_bot = gbot;
  s_dg_ready = true;
}

// Test whether column `col` of digit d has ink in ANY source row of [sy0, sy1].
static inline bool prv_dg_col_ink(int d, int col, int sy0, int sy1) {
  for (int sy = sy0; sy <= sy1; sy++) {
    if (s_dg_mask[d][sy * DG_ROWBYTES + (col >> 3)] & (1 << (col & 7))) return true;
  }
  return false;
}

// Draw one squashed destination row: the UNION of every source row that maps into it.
// Unioning (instead of picking a single sampled row) keeps a squashed digit solid so it
// reads as a flattened glyph ("espalmado") rather than a set of dropped-out stripes.
static void prv_dg_draw_row_range(GContext *ctx, int d, int sy0, int sy1, int xL, int dest_y) {
  if (sy0 < 0) sy0 = 0;
  if (sy1 >= DG_H) sy1 = DG_H - 1;
  if (sy1 < sy0) sy1 = sy0;
  int col = 0;
  while (col < DG_W) {
    if (prv_dg_col_ink(d, col, sy0, sy1)) {
      int start = col;
      while (col < DG_W && prv_dg_col_ink(d, col, sy0, sy1)) col++;
      graphics_draw_line(ctx, GPoint(xL + start, dest_y), GPoint(xL + col - 1, dest_y));
    } else {
      col++;
    }
  }
}

// Map destination row r (of h) back to the source-row band it covers, so no ink is dropped.
static inline void prv_dg_src_band(int r, int h, int ink_h, int *sy0, int *sy1) {
  *sy0 = s_dg_ink_top + (int)((long)r * ink_h / h);
  *sy1 = s_dg_ink_top + (int)((long)(r + 1) * ink_h / h) - 1;
  if (*sy1 < *sy0) *sy1 = *sy0;
}

// Blit digit d squashed to `h` of its full ink height, anchored at top_y (grows downward).
static void prv_dg_blit_top(GContext *ctx, int d, int xL, int top_y, int h, int ink_h) {
  if (h <= 0) return;
  for (int r = 0; r < h; r++) {
    int sy0, sy1;
    prv_dg_src_band(r, h, ink_h, &sy0, &sy1);
    prv_dg_draw_row_range(ctx, d, sy0, sy1, xL, top_y + r);
  }
}

// Blit digit d squashed to `h` of its full ink height, anchored at base_y (shrinks to base).
static void prv_dg_blit_bottom(GContext *ctx, int d, int xL, int base_y, int h, int ink_h) {
  if (h <= 0) return;
  for (int r = 0; r < h; r++) {
    int sy0, sy1;
    prv_dg_src_band(r, h, ink_h, &sy0, &sy1);
    prv_dg_draw_row_range(ctx, d, sy0, sy1, xL, base_y - h + 1 + r);
  }
}

// Overdraw the changed digit columns of the distance with the squash morph. The static
// LECO number (new value) must already have been drawn — this only touches columns whose
// digit differs between old and new, keeping unchanged columns pixel-identical to the font.
static void prv_dg_draw_morph(GContext *ctx, GRect bounds, GColor bg) {
  GColor fg = prv_distance_fg_for_bg(bg);
  int lo = strlen(s_dg_old);
  int ln = strlen(s_dg_new);
  int ncols = (lo > ln) ? lo : ln;
  int top_y = bounds.origin.y + s_dg_ink_top;
  int base_y = bounds.origin.y + s_dg_ink_bot;
  int ink_h = s_dg_ink_bot - s_dg_ink_top + 1;

  // Compute the exact start x-coordinate of the new string (s_dg_new) as drawn by the system
  int x_start = 0;
#if defined(PBL_PLATFORM_EMERY)
  // Emery (PT2): Stacked layout
  GFont num_font = fonts_get_system_font(DISTANCE_FONT);
  GRect num_rect = GRect(bounds.origin.x, bounds.origin.y, bounds.size.w, 38);
  GSize num_size = graphics_text_layout_get_content_size(
    s_dg_new, num_font, num_rect, GTextOverflowModeWordWrap, GTextAlignmentRight
  );
  x_start = num_rect.origin.x + num_rect.size.w - num_size.w;
#else
  // Other platforms: Side-by-side layout
  char unit[16];
  char num_split[16];
  prv_split_distance(s_dg_new, num_split, sizeof(num_split), unit, sizeof(unit));

  // Convert unit to uppercase to match mockup design
  for (int i = 0; unit[i] != '\0'; i++) {
    if (unit[i] >= 'a' && unit[i] <= 'z') {
      unit[i] = unit[i] - 'a' + 'A';
    }
  }

  int spacing = 1;
  int block_w = 2;
  int len = strlen(unit);
  int unit_w = (unit[0] != '\0') ? (len * 3 * block_w + (len - 1) * spacing) : 0;

  GFont num_font = fonts_get_system_font(DISTANCE_FONT);
  GSize num_size = graphics_text_layout_get_content_size(
    num_split, num_font, GRect(0, 0, bounds.size.w, bounds.size.h),
    GTextOverflowModeWordWrap, GTextAlignmentLeft
  );

  int total_w = num_size.w + 4 + unit_w;
  x_start = bounds.origin.x + bounds.size.w - total_w - 4;
#endif

  for (int k = 0; k < ncols; k++) {                 // k = 0 is the rightmost column
    char oc = (k < lo) ? s_dg_old[lo - 1 - k] : 0;
    char nc = (k < ln) ? s_dg_new[ln - 1 - k] : 0;
    if (oc == nc) continue;                          // unchanged: keep the static font draw

    int xL = x_start + (ln - 1 - k) * s_dg_adv;
    // Erase the column cell (down to just below the base) so the static full-height digit
    // is removed before we paint the morphing pair.
    graphics_context_set_fill_color(ctx, bg);
    graphics_fill_rect(ctx, GRect(xL, bounds.origin.y, s_dg_adv,
                                  base_y - bounds.origin.y + 3), 0, GCornerNone);

    // Ease-in-out (smoothstep) the linear progress so the morph starts and ends gently.
    // A linear ramp made the first tick jump ~10% instantly, which read as the old digit
    // "lurching down" to the base before the smooth part — this softens that opening step.
    int p = s_dg_pct;
    int eased = (300 * p * p - 2 * p * p * p) / 10000;   // 3t^2 - 2t^3, scaled to 0..100
    if (eased < 0) eased = 0; else if (eased > 100) eased = 100;
    int h_new = ink_h * eased / 100;
    int h_old = ink_h - h_new;

    graphics_context_set_stroke_color(ctx, fg);
    // Old digit shrinks down to the base; new digit grows down from the top.
    // They meet exactly at the moving boundary, only scaling on the vertical axis.
    if (oc >= '0' && oc <= '9') {
      prv_dg_blit_bottom(ctx, oc - '0', xL, base_y, h_old, ink_h);
    }
    if (nc >= '0' && nc <= '9') {
      prv_dg_blit_top(ctx, nc - '0', xL, top_y, h_new, ink_h);
    }
  }
}

static void prv_dg_stop(void) {
  s_dg_active = false;
  if (s_dg_timer) { app_timer_cancel(s_dg_timer); s_dg_timer = NULL; }
}

static void prv_dg_timer_cb(void *ctx) {
  s_dg_timer = NULL;
  s_dg_pct += 5;                                     // 20 steps x 12 ms ~= 240 ms (finer, so
                                                     // the eased curve reads as smooth motion
                                                     // instead of discrete 10% jumps)
  if (s_dg_pct >= 100) {
    s_dg_pct = 100;
    s_dg_active = false;                             // final frame falls back to pure font
  } else {
    s_dg_timer = app_timer_register(12, prv_dg_timer_cb, NULL);
  }
  if (s_panel_layer) layer_mark_dirty(s_panel_layer);
}

// Begin a digit morph from old_text -> new_text. Only pure-digit numbers with an unchanged
// unit qualify (so street/decimal/unit-cross cases fall back to a plain redraw).
#define DG_ANIM_ENABLED 1

static void prv_dg_start(const char *old_text, const char *new_text) {
  if (!DG_ANIM_ENABLED) return;

  char on[16], ou[16], nn[16], nu[16];
  prv_split_distance(old_text, on, sizeof(on), ou, sizeof(ou));
  prv_split_distance(new_text, nn, sizeof(nn), nu, sizeof(nu));

  if (!prv_dg_all_digits(on) || !prv_dg_all_digits(nn)) { prv_dg_stop(); return; }
  if (strcmp(ou, nu) != 0) { prv_dg_stop(); return; }   // unit changed -> no morph (yet)
  if (strcmp(on, nn) == 0) return;                       // identical -> nothing to do

  strncpy(s_dg_old, on, sizeof(s_dg_old) - 1); s_dg_old[sizeof(s_dg_old) - 1] = '\0';
  strncpy(s_dg_new, nn, sizeof(s_dg_new) - 1); s_dg_new[sizeof(s_dg_new) - 1] = '\0';
  s_dg_pct = 0;
  s_dg_active = true;
  if (s_dg_timer) app_timer_cancel(s_dg_timer);
  s_dg_timer = app_timer_register(20, prv_dg_timer_cb, NULL);
}
#endif // PBL_COLOR

static void prv_draw_speedometer(GContext *ctx, GRect bounds, GColor bg_color, GRect clip_rect) {
  if (s_current_speed < 0) {
    return;
  }
  
  char speed_buf[16];
  snprintf(speed_buf, sizeof(speed_buf), "%d", s_current_speed);
  
  GColor text_color = prv_distance_fg_for_bg(bg_color);
  graphics_context_set_text_color(ctx, text_color);
  graphics_context_set_fill_color(ctx, text_color);
  
  GFont chosen_font = fonts_get_system_font(FONT_KEY_LECO_20_BOLD_NUMBERS);
  const char* font_keys[] = {
    FONT_KEY_LECO_42_NUMBERS,
    FONT_KEY_LECO_38_BOLD_NUMBERS,
    FONT_KEY_LECO_36_BOLD_NUMBERS,
    FONT_KEY_LECO_32_BOLD_NUMBERS,
    FONT_KEY_LECO_28_LIGHT_NUMBERS,
    FONT_KEY_LECO_20_BOLD_NUMBERS
  };
  
  int max_allowed_w = (int)(bounds.size.w * 0.85);
  int num_h = 20; // default height fallback
  
  for (size_t i = 0; i < sizeof(font_keys)/sizeof(font_keys[0]); i++) {
    GFont f = fonts_get_system_font(font_keys[i]);
    GSize size = graphics_text_layout_get_content_size(speed_buf, f, bounds, 
                                                      GTextOverflowModeWordWrap, GTextAlignmentCenter);
    if (size.w <= max_allowed_w) {
      chosen_font = f;
      if (i == 0) num_h = 42;
      else if (i == 1) num_h = 38;
      else if (i == 2) num_h = 36;
      else if (i == 3) num_h = 32;
      else if (i == 4) num_h = 28;
      else num_h = 20;
      break;
    }
  }
  
  // Set block size based on platform
#if defined(PBL_PLATFORM_EMERY)
  int block_w = 4;
  int block_h = 4;
  int spacing = 3;
  int gap = 6;
#else
  int block_w = 2;
  int block_h = 2;
  int spacing = 1;
  int gap = 4;
#endif

  int unit_len = 4; // "KM/H" length is 4
  int unit_w = unit_len * 3 * block_w + (unit_len - 1) * spacing;
  
  // Calculate total height of speedometer (number height + gap + 5 blocks height)
  int total_h = num_h + gap + 5 * block_h;
  int y_offset = (bounds.size.h - total_h) / 2;
  if (y_offset < 0) {
    y_offset = 0;
  }
  
  // Draw speed number centered horizontally
  GRect num_rect = GRect(bounds.origin.x, bounds.origin.y + y_offset, bounds.size.w, num_h + 4);
  grect_clip(&num_rect, &clip_rect);
  if (num_rect.size.w > 0 && num_rect.size.h > 0) {
    graphics_draw_text(ctx, speed_buf, chosen_font, num_rect, 
                       GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
  }
  
  // Draw km/h unit centered horizontally below number
  int start_x = bounds.origin.x + (bounds.size.w - unit_w) / 2;
  int start_y = bounds.origin.y + y_offset + num_h + gap;
  prv_draw_leco_string(ctx, "KM/H", GPoint(start_x, start_y), block_w, block_h, spacing, clip_rect);
}

static void prv_panel_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
#if Steer_Has_Transitions || defined(PBL_COLOR)
  int w = bounds.size.w;
#endif
#if Steer_Has_Transitions
  int h = bounds.size.h;
#endif

#if defined(PBL_COLOR)
  // Capture the LECO digit glyphs the first time a morph needs them. Done before any real
  // content is drawn so the scratch render is overpainted within this same frame.
  if (s_dg_active && !s_dg_ready) prv_dg_capture(ctx, w);
#endif

  if (s_anim_state == ANIM_STATE_TRANSITIONING) {
#if USE_CARDS_TRANSITION
    // Cards-style transitions (background vertical wipe, vector icon morph to/from square, text slide)
#if Steer_Has_Transitions
    // 1. Draw background wipe
    int y = (s_anim_pct * h) / 100;
    GColor old_bg_resolved = prv_resolve_bg_color(s_prev_bg_color);
    GColor new_bg_resolved = prv_resolve_bg_color(s_bg_color);

    if (s_anim_slide_dir == SLIDE_DIR_LEFT_TO_RIGHT) {
      // Wipe top-to-bottom: top is new, bottom is old
      graphics_context_set_fill_color(ctx, new_bg_resolved);
      graphics_fill_rect(ctx, GRect(0, 0, w, y), 0, GCornerNone);
      graphics_context_set_fill_color(ctx, old_bg_resolved);
      graphics_fill_rect(ctx, GRect(0, y, w, h - y), 0, GCornerNone);
    } else {
      // Wipe bottom-to-top: top is old, bottom is new
      graphics_context_set_fill_color(ctx, old_bg_resolved);
      graphics_fill_rect(ctx, GRect(0, 0, w, h - y), 0, GCornerNone);
      graphics_context_set_fill_color(ctx, new_bg_resolved);
      graphics_fill_rect(ctx, GRect(0, h - y, w, y), 0, GCornerNone);
    }

    // 2. Draw icon & text depending on animation progress
    if (s_anim_pct <= 50) {
      // First half: draw old icon morphing to square, old text sliding out
      int slide_pct = s_anim_pct * 2;
      int morph_pct = 0;
      if (s_anim_pct > 20 && s_anim_pct <= 50) {
        morph_pct = (s_anim_pct - 20) * 100 / 30;
      }
      int text_dy = -(slide_pct * 20 / 100);

      // Mutate prev icon to morph state and draw it
      if (s_prev_pdc_image) {
        prv_icon_apply_morph(s_prev_pdc_image, morph_pct);
      }
      prv_draw_icon(ctx, ICON_RELATIVE_RECT, 0, s_prev_has_forwarded, s_prev_forwarded_icon_bytes, s_prev_pdc_image, s_prev_maneuver_index, 100);

      // Draw old text
      GRect old_dist_rect = DISTANCE_RELATIVE_RECT; old_dist_rect.origin.y += text_dy;
      GRect old_street_rect = STREET_RELATIVE_RECT; old_street_rect.origin.y += text_dy;
      prv_draw_distance(ctx, old_dist_rect, 0, s_prev_distance_text, old_bg_resolved, bounds);
      
      if (STEER_SHOW_SPEEDOMETER && s_current_speed >= 0) {
        prv_draw_speedometer(ctx, old_street_rect, old_bg_resolved, bounds);
      } else {
        prv_draw_street(ctx, old_street_rect, 0, s_prev_street_text, old_bg_resolved, bounds);
      }
    } else {
      // Second half: draw new icon morphing from square, new text sliding in
      int slide_pct = (100 - s_anim_pct) * 2;
      int morph_pct = 0;
      if (s_anim_pct > 50 && s_anim_pct < 80) {
        morph_pct = (80 - s_anim_pct) * 100 / 30;
      }
      int text_dy = (slide_pct * 8 / 100);

      // Mutate active icon to morph state and draw it
      if (s_active_pdc_image) {
        prv_icon_apply_morph(s_active_pdc_image, morph_pct);
      }
      prv_draw_icon(ctx, ICON_RELATIVE_RECT, 0, s_has_forwarded_icon, s_forwarded_icon_bytes, s_active_pdc_image, s_maneuver_index, 100);

      // Draw new text
      GRect new_dist_rect = DISTANCE_RELATIVE_RECT; new_dist_rect.origin.y += text_dy;
      GRect new_street_rect = STREET_RELATIVE_RECT; new_street_rect.origin.y += text_dy;
      prv_draw_distance(ctx, new_dist_rect, 0, s_distance_text, new_bg_resolved, bounds);
      
      if (STEER_SHOW_SPEEDOMETER && s_current_speed >= 0) {
        prv_draw_speedometer(ctx, new_street_rect, new_bg_resolved, bounds);
      } else {
        prv_draw_street(ctx, new_street_rect, 0, s_street_text, new_bg_resolved, bounds);
      }
    }
#endif
#else
    const bool horizontal = (s_anim_slide_dir != SLIDE_DIR_BOTTOM_TO_TOP);

    // Staggered frame indices on the 10-frame moook timeline: the background leads,
    // the icon trails by one frame and the text by two, for a layered depth effect.
    int frame_idx = s_anim_pct / 10;
    if (frame_idx < 0) frame_idx = 0;
    if (frame_idx > MOOOK_FRAMES) frame_idx = MOOOK_FRAMES;
    int fi_bg = frame_idx;
    int fi_icon = frame_idx - 1; if (fi_icon < 0) fi_icon = 0;
    int fi_text = frame_idx - 2; if (fi_text < 0) fi_text = 0;
    int32_t norm_bg   = (int32_t)fi_bg   * 65535 / MOOOK_FRAMES;
    int32_t norm_icon = (int32_t)fi_icon * 65535 / MOOOK_FRAMES;
    int32_t norm_text = (int32_t)fi_text * 65535 / MOOOK_FRAMES;

    // Where the old card exits to, and where the new card enters from, along the axis.
    int16_t old_to, new_from;
    switch (s_anim_slide_dir) {
      case SLIDE_DIR_LEFT_TO_RIGHT: old_to =  w; new_from = -w; break;
      case SLIDE_DIR_BOTTOM_TO_TOP: old_to = -h; new_from =  h; break;
      case SLIDE_DIR_RIGHT_TO_LEFT:
      default:                      old_to = -w; new_from =  w; break;
    }

    int off_old  = (int)prv_interpolate_moook(norm_bg,   0, old_to);
    int off_bg   = (int)prv_interpolate_moook(norm_bg,   new_from, 0);
    int off_icon = (int)prv_interpolate_moook(norm_icon, new_from, 0);
    int off_text = (int)prv_interpolate_moook(norm_text, new_from, 0);

    int dxo = horizontal ? off_old  : 0, dyo = horizontal ? 0 : off_old;
    int dxb = horizontal ? off_bg   : 0, dyb = horizontal ? 0 : off_bg;
    int dxi = horizontal ? off_icon : 0, dyi = horizontal ? 0 : off_icon;
    int dxt = horizontal ? off_text : 0, dyt = horizontal ? 0 : off_text;

    // 1. Old card slides out as a single unit.
    GRect old_bounds = GRect(bounds.origin.x + dxo, bounds.origin.y + dyo, w, h);
    GColor old_bg_resolved = prv_resolve_bg_color(s_prev_bg_color);
    graphics_context_set_fill_color(ctx, old_bg_resolved);
    graphics_fill_rect(ctx, old_bounds, 0, GCornerNone);

    GRect old_icon_rect = GRect(ICON_RELATIVE_RECT.origin.x + dxo, ICON_RELATIVE_RECT.origin.y + dyo, ICON_RELATIVE_RECT.size.w, ICON_RELATIVE_RECT.size.h);
    GRect old_dist_rect = GRect(DISTANCE_RELATIVE_RECT.origin.x + dxo, DISTANCE_RELATIVE_RECT.origin.y + dyo, DISTANCE_RELATIVE_RECT.size.w, DISTANCE_RELATIVE_RECT.size.h);
    GRect old_street_rect = GRect(STREET_RELATIVE_RECT.origin.x + dxo, STREET_RELATIVE_RECT.origin.y + dyo, STREET_RELATIVE_RECT.size.w, STREET_RELATIVE_RECT.size.h);

    prv_draw_icon(ctx, old_icon_rect, 0, s_prev_has_forwarded, s_prev_forwarded_icon_bytes, s_prev_pdc_image, s_prev_maneuver_index, 100);
    prv_draw_distance(ctx, old_dist_rect, 0, s_prev_distance_text, old_bg_resolved, old_bounds);
    if (STEER_SHOW_SPEEDOMETER && s_current_speed >= 0) {
      prv_draw_speedometer(ctx, old_street_rect, old_bg_resolved, old_bounds);
    } else {
      prv_draw_street(ctx, old_street_rect, 0, s_prev_street_text, old_bg_resolved, old_bounds);
    }

    // 2. New card enters with staggered layers: background leads, icon then text trail.
    //    Each element is clipped to the new background rect so text/icon emerge from it.
    GRect new_bounds = GRect(bounds.origin.x + dxb, bounds.origin.y + dyb, w, h);
    GColor new_bg_resolved = prv_resolve_bg_color(s_bg_color);
    graphics_context_set_fill_color(ctx, new_bg_resolved);
    graphics_fill_rect(ctx, new_bounds, 0, GCornerNone);

    GRect new_icon_rect = GRect(ICON_RELATIVE_RECT.origin.x + dxi, ICON_RELATIVE_RECT.origin.y + dyi, ICON_RELATIVE_RECT.size.w, ICON_RELATIVE_RECT.size.h);
    GRect new_dist_rect = GRect(DISTANCE_RELATIVE_RECT.origin.x + dxt, DISTANCE_RELATIVE_RECT.origin.y + dyt, DISTANCE_RELATIVE_RECT.size.w, DISTANCE_RELATIVE_RECT.size.h);
    GRect new_street_rect = GRect(STREET_RELATIVE_RECT.origin.x + dxt, STREET_RELATIVE_RECT.origin.y + dyt, STREET_RELATIVE_RECT.size.w, STREET_RELATIVE_RECT.size.h);

    prv_draw_icon(ctx, new_icon_rect, 0, s_has_forwarded_icon, s_forwarded_icon_bytes, s_active_pdc_image, s_maneuver_index, 100);
    prv_draw_distance(ctx, new_dist_rect, 0, s_distance_text, new_bg_resolved, new_bounds);
    if (STEER_SHOW_SPEEDOMETER && s_current_speed >= 0) {
      prv_draw_speedometer(ctx, new_street_rect, new_bg_resolved, new_bounds);
    } else {
      prv_draw_street(ctx, new_street_rect, 0, s_street_text, new_bg_resolved, new_bounds);
    }
#endif
  } else {
    // IDLE
    GColor bg_resolved = prv_resolve_bg_color(s_bg_color);
    graphics_context_set_fill_color(ctx, bg_resolved);
    graphics_fill_rect(ctx, bounds, 0, GCornerNone);
    
    // Waiting for signal: show the Steer car-rental branding icon instead of the
    // procedural chevron. Once a maneuver arrives (index >= 0) the normal icon draws.
    if (s_maneuver_index < 0 && !s_has_forwarded_icon && s_wait_pdc_image) {
      gdraw_command_image_draw(ctx, s_wait_pdc_image, ICON_RELATIVE_RECT.origin);
    } else {
      prv_draw_icon(ctx, ICON_RELATIVE_RECT, 0, s_has_forwarded_icon, s_forwarded_icon_bytes, s_active_pdc_image, s_maneuver_index, 100);
    }
    prv_draw_distance(ctx, DISTANCE_RELATIVE_RECT, 0, s_distance_text, bg_resolved, bounds);
#if defined(PBL_COLOR)
    // Overdraw the changed digit columns with the squash morph (unchanged columns keep the
    // exact LECO font draw above). The final settled frame skips this -> pixel-identical.
    if (s_dg_active && s_dg_ready) {
      prv_dg_draw_morph(ctx, DISTANCE_RELATIVE_RECT, bg_resolved);
    }
#endif
    if (STEER_SHOW_SPEEDOMETER && s_current_speed >= 0) {
      prv_draw_speedometer(ctx, STREET_RELATIVE_RECT, bg_resolved, bounds);
    } else {
      prv_draw_street(ctx, STREET_RELATIVE_RECT, 0, s_street_text, bg_resolved, bounds);
    }
  }

#if defined(PBL_ROUND)
  // Draw ETA centered near the bottom of circular screens (Chalk)
  if (s_eta_text[0] != '\0') {
    GColor eta_color = prv_distance_fg_for_bg(prv_resolve_bg_color(s_bg_color));
    graphics_context_set_text_color(ctx, eta_color);
    GFont eta_font = fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD);
    graphics_draw_text(ctx, s_eta_text, eta_font, ETA_RELATIVE_RECT, 
                       GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
  }
#endif
}

// ---- PDC icon appear animation (vector draw-on point by point) ----
#define ICON_PTS_CAP 512
#define ICON_CMDS_CAP 64
#define ICON_ANIM_MIN_PCT 0
#define ICON_ANIM_STEP_MS 25
#define ICON_ANIM_STEP_PCT 5

#if Steer_Has_Transitions
static GPoint s_icon_pts_orig[ICON_PTS_CAP];
static int s_icon_pts_count = 0;
static uint16_t s_icon_radii_orig[ICON_CMDS_CAP];
static int s_icon_circle_count = 0;
#endif

// Content-fit: every icon (regardless of how much of its 80x80 canvas it fills)
// is scaled around its own content centre to fill the icon layer, then re-centred
// on the layer. This makes all icons the same on-screen size and as large as the
// layer allows. Stroke width is left untouched (kept at the approved px). Values
// are recomputed per icon in prv_icon_compute_fit() from the raw (un-scaled) PDC.
static int32_t s_fit_num = 256;   // content-fit scale * 256 (fixed point)
static int s_fit_cx = 40, s_fit_cy = 40;   // content centre, raw PDC coords
static int s_box_cx = 40, s_box_cy = 40;   // layer centre (draw target)

#if !USE_CARDS_TRANSITION
// Squash & stretch physics, indexed by the 10-frame moook timeline.
// {width%, height%}: the icon stretches along the slide axis on the way in, then
// overshoots/squashes on impact and settles back to 100/100. For vertical slides
// (bottom-to-top) the two columns are swapped so the stretch runs along Y.
static const int8_t s_moook_squash_stretch[][2] = {
  {100, 100}, // Frame 0
  {105, 95},  // Frame 1
  {115, 85},  // Frame 2 (max stretch)
  {90, 110},  // Frame 3 (impact overshoot/squash)
  {95, 105},  // Frame 4
  {102, 98},  // Frame 5
  {99, 101},  // Frame 6
  {100, 100}, // Frame 7
  {100, 100}, // Frame 8
  {100, 100}, // Frame 9
  {100, 100}  // Frame 10
};
#endif

static bool prv_icon_scale_once_iter(GDrawCommand *command, uint32_t index, void *context) {
  GDrawCommandType type = gdraw_command_get_type(command);
  uint16_t n = gdraw_command_get_num_points(command);
  
  for (uint16_t i = 0; i < n; i++) {
    GPoint o = gdraw_command_get_point(command, i);
    int32_t dx = ((int32_t)o.x - s_fit_cx) * s_fit_num / 256;
    int32_t dy = ((int32_t)o.y - s_fit_cy) * s_fit_num / 256;
    gdraw_command_set_point(command, i, GPoint(s_box_cx + dx, s_box_cy + dy));
  }
  
  if (type == GDrawCommandTypeCircle) {
    uint16_t orig_r = gdraw_command_get_radius(command);
    uint16_t scaled_r = orig_r * s_fit_num / 256;
    gdraw_command_set_radius(command, scaled_r);
  }
  return true;
}

static void prv_icon_scale_once(GDrawCommandImage *image) {
  if (!image) return;
  GDrawCommandList *list = gdraw_command_image_get_command_list(image);
  if (!list) return;
  gdraw_command_list_iterate(list, prv_icon_scale_once_iter, NULL);
}

#if Steer_Has_Transitions
typedef struct {
  int idx;
  bool snapshot;   // true: read original points; false: write scaled points
  int32_t scale_pct;
  int32_t visible_pct;
  int32_t factor_x;
  int32_t factor_y;
} IconScaleCtx;

static bool prv_icon_scale_iter(GDrawCommand *command, uint32_t index, void *context) {
  IconScaleCtx *c = (IconScaleCtx *)context;
  GDrawCommandType type = gdraw_command_get_type(command);
  uint16_t n = gdraw_command_get_num_points(command);
  
  if (c->snapshot) {
    for (uint16_t i = 0; i < n; i++) {
      if (c->idx < ICON_PTS_CAP) {
        s_icon_pts_orig[c->idx++] = gdraw_command_get_point(command, i);
      }
    }
    if (type == GDrawCommandTypeCircle) {
      if (s_icon_circle_count < ICON_CMDS_CAP) {
        s_icon_radii_orig[s_icon_circle_count++] = gdraw_command_get_radius(command);
      }
    }
  } else {
    int total_visible = s_icon_pts_count * c->visible_pct / 100;
    int start_idx = c->idx;
    c->idx += n;
    
    // Compute content-fit scaled points scaled by scale_pct
    GPoint scaled_pts[n];
    for (uint16_t i = 0; i < n; i++) {
      if (start_idx + i < ICON_PTS_CAP) {
        GPoint o = s_icon_pts_orig[start_idx + i];
        int32_t dx = ((int32_t)o.x - s_fit_cx) * s_fit_num * c->scale_pct * c->factor_x / 2560000;
        int32_t dy = ((int32_t)o.y - s_fit_cy) * s_fit_num * c->scale_pct * c->factor_y / 2560000;
        scaled_pts[i] = GPoint(s_box_cx + dx, s_box_cy + dy);
      } else {
        scaled_pts[i] = GPoint(0, 0);
      }
    }
    
    if (type == GDrawCommandTypeCircle) {
      if (start_idx < total_visible) {
        gdraw_command_set_hidden(command, false);
        gdraw_command_set_point(command, 0, scaled_pts[0]);
        int circle_idx = s_icon_circle_count++;
        if (circle_idx < ICON_CMDS_CAP) {
          uint16_t orig_r = s_icon_radii_orig[circle_idx];
          uint16_t scaled_r = orig_r * s_fit_num * c->scale_pct * c->factor_x / 2560000;
          gdraw_command_set_radius(command, scaled_r);
        }
      } else {
        gdraw_command_set_hidden(command, true);
      }
    } else {
      if (start_idx >= total_visible) {
        gdraw_command_set_hidden(command, true);
      } else {
        gdraw_command_set_hidden(command, false);
        if (start_idx + n <= total_visible) {
          for (uint16_t i = 0; i < n; i++) {
            gdraw_command_set_point(command, i, scaled_pts[i]);
          }
        } else {
          uint16_t cmd_visible_points = total_visible - start_idx;
          if (cmd_visible_points < 1) {
            cmd_visible_points = 1;
          }
          for (uint16_t i = 0; i < cmd_visible_points; i++) {
            gdraw_command_set_point(command, i, scaled_pts[i]);
          }
          GPoint last_pt = scaled_pts[cmd_visible_points - 1];
          for (uint16_t i = cmd_visible_points; i < n; i++) {
            gdraw_command_set_point(command, i, last_pt);
          }
        }
      }
    }
  }
  return true;
}
#endif

#define ICON_PDC_CANVAS_FALLBACK 80
static void prv_icon_compute_fit(void) {
  int canvas = ICON_PDC_CANVAS_FALLBACK;
  if (s_active_pdc_image) {
    GSize vb = gdraw_command_image_get_bounds_size(s_active_pdc_image);
    if (vb.w > 0 && vb.h > 0) {
      canvas = (vb.w < vb.h) ? vb.w : vb.h;
    }
  }
  s_fit_num = 256;
  s_fit_cx = canvas / 2; s_fit_cy = canvas / 2;
  s_box_cx = canvas / 2; s_box_cy = canvas / 2;
  if (!s_active_pdc_image) {
    return;
  }
  GRect b = ICON_RELATIVE_RECT;
  s_box_cx = b.size.w / 2;
  s_box_cy = b.size.h / 2;
  int box = (b.size.w < b.size.h) ? b.size.w : b.size.h;
  s_fit_num = box * 256 / canvas;   // uniform: NxN PDC canvas -> layer box
}

#if Steer_Has_Transitions
static void prv_icon_snapshot_points(void) {
  s_icon_pts_count = 0;
  s_icon_circle_count = 0;
  if (!s_active_pdc_image) {
    return;
  }
  GDrawCommandList *list = gdraw_command_image_get_command_list(s_active_pdc_image);
  if (!list) {
    return;
  }
  IconScaleCtx ctx = { .idx = 0, .snapshot = true, .scale_pct = 100, .visible_pct = 100 };
  gdraw_command_list_iterate(list, prv_icon_scale_iter, &ctx);
  s_icon_pts_count = ctx.idx;
}

#if !USE_CARDS_TRANSITION
// factor_x/factor_y are the squash & stretch percentages (100 = no deformation).
// They must never be 0: a 0 factor collapses every point onto the box centre.
static void prv_icon_apply_scale(GDrawCommandImage *image, int32_t scale_pct, int32_t visible_pct,
                                 int32_t factor_x, int32_t factor_y) {
  if (!image || s_icon_pts_count == 0) {
    return;
  }
  GDrawCommandList *list = gdraw_command_image_get_command_list(image);
  if (!list) {
    return;
  }
  if (factor_x <= 0) factor_x = 100;
  if (factor_y <= 0) factor_y = 100;
  s_icon_circle_count = 0;
  IconScaleCtx ctx = { .idx = 0, .snapshot = false, .scale_pct = scale_pct,
                       .visible_pct = visible_pct, .factor_x = factor_x, .factor_y = factor_y };
  gdraw_command_list_iterate(list, prv_icon_scale_iter, &ctx);
}
#endif

static int16_t prv_int_attract_to(int16_t i, int16_t bounds, int32_t pct) {
  const int16_t delta_0 = (int16_t) ((0 + 1) - i);
  const int16_t delta_b = (int16_t) ((bounds - 1) - i);
  int16_t diff_0 = delta_0 > 0 ? delta_0 : -delta_0;
  int16_t diff_b = delta_b > 0 ? delta_b : -delta_b;
  const int16_t delta = diff_0 < diff_b ? delta_0 : delta_b;
  return (int16_t) (i + delta * pct / 100);
}

static bool prv_icon_morph_iter(GDrawCommand *command, uint32_t index, void *context) {
  IconScaleCtx *c = (IconScaleCtx *)context;
  GDrawCommandType type = gdraw_command_get_type(command);
  uint16_t n = gdraw_command_get_num_points(command);
  
  int start_idx = c->idx;
  c->idx += n;
  
  GPoint scaled_pts[n];
  for (uint16_t i = 0; i < n; i++) {
    if (start_idx + i < ICON_PTS_CAP) {
      GPoint o = s_icon_pts_orig[start_idx + i];
      
      int box_w = s_box_cx * 2;
      int box_h = s_box_cy * 2;
      
      int16_t local_x = o.x;
      int16_t local_y = o.y + 1;
      
      int16_t attracted_x = prv_int_attract_to(local_x, box_w, c->scale_pct);
      int16_t attracted_y = prv_int_attract_to(local_y, box_h, c->scale_pct);
      
      scaled_pts[i] = GPoint(attracted_x, attracted_y);
    } else {
      scaled_pts[i] = GPoint(0, 0);
    }
  }
  
  if (type == GDrawCommandTypeCircle) {
    if (start_idx < s_icon_pts_count) {
      gdraw_command_set_hidden(command, false);
      gdraw_command_set_point(command, 0, scaled_pts[0]);
    } else {
      gdraw_command_set_hidden(command, true);
    }
  } else {
    gdraw_command_set_hidden(command, false);
    for (uint16_t i = 0; i < n; i++) {
      gdraw_command_set_point(command, i, scaled_pts[i]);
    }
  }
  return true;
}

static void prv_icon_apply_morph(GDrawCommandImage *image, int32_t morph_pct) {
  if (!image || s_icon_pts_count == 0) {
    return;
  }
  GDrawCommandList *list = gdraw_command_image_get_command_list(image);
  if (!list) {
    return;
  }
  IconScaleCtx ctx = { .idx = 0, .snapshot = false, .scale_pct = morph_pct,
                       .visible_pct = 100, .factor_x = 100, .factor_y = 100 };
  gdraw_command_list_iterate(list, prv_icon_morph_iter, &ctx);
}
#endif

// Loads the car-rental branding PDC and content-fits it to the icon layer box,
// reusing the maneuver-icon fit pipeline. Points are mutated in place on the
// wait image, so it stays correctly scaled regardless of later maneuver fits.
static void prv_wait_icon_init(void) {
  if (s_wait_pdc_image) {
    return;
  }
  s_wait_pdc_image = gdraw_command_image_create_with_resource(RESOURCE_ID_PDC_WAIT_ICON);
  if (!s_wait_pdc_image) {
    return;
  }
  GDrawCommandImage *saved = s_active_pdc_image;
  s_active_pdc_image = s_wait_pdc_image;   // fit pipeline reads this global
  prv_icon_compute_fit();
  prv_icon_scale_once(s_wait_pdc_image);
  s_active_pdc_image = saved;
}

static void prv_render_forwarded_icon(GBitmap *bitmap, const uint8_t *icon_bytes, int scale_pct) {
  if (!bitmap || !icon_bytes) {
    return;
  }
  
  uint16_t stride = gbitmap_get_bytes_per_row(bitmap);
  uint8_t *dst = gbitmap_get_data(bitmap);
  GSize size = gbitmap_get_bounds(bitmap).size;
  int dim = size.w;
  
  memset(dst, 0x00, (size_t)stride * dim);

#if defined(PBL_COLOR)
  const bool invert_icon = false;
#else
  const bool invert_icon = true;
#endif

  #define FWD_ON(sx, sy) \
    ((((icon_bytes[(sy) * 8 + ((sx) >> 3)] >> ((sx) & 7)) & 1) != 0) != invert_icon)

  int cminx = 48, cminy = 48, cmaxx = -1, cmaxy = -1;
  for (int sy = 0; sy < 48; sy++) {
    for (int sx = 0; sx < 48; sx++) {
      if (FWD_ON(sx, sy)) {
        if (sx < cminx) cminx = sx;
        if (sx > cmaxx) cmaxx = sx;
        if (sy < cminy) cminy = sy;
        if (sy > cmaxy) cmaxy = sy;
      }
    }
  }

  if (cmaxx >= cminx) {
    int cw = cmaxx - cminx + 1;
    int ch = cmaxy - cminy + 1;
    int span = (cw > ch) ? cw : ch;
    
    int target = (dim * 94 / 100) * scale_pct / 100;
    if (target < 1) target = 1;
    
    int s256 = target * 256 / span;     // content scale, fixed point
    if (s256 < 1) s256 = 1;
    
    int dcw = cw * s256 / 256;
    int dch = ch * s256 / 256;
    int offx = (dim - dcw) / 2;
    int offy = (dim - dch) / 2;

    for (int dy = 0; dy < dim; dy++) {
      int rely = dy - offy;
      if (rely < 0 || rely >= dch) continue;
      int sy = cminy + rely * 256 / s256;
      if (sy > cmaxy) sy = cmaxy;
      uint8_t *drow = dst + dy * stride;
      for (int dx = 0; dx < dim; dx++) {
        int relx = dx - offx;
        if (relx < 0 || relx >= dcw) continue;
        int sx = cminx + relx * 256 / s256;
        if (sx > cmaxx) sx = cmaxx;
        if (FWD_ON(sx, sy)) {
          drow[dx >> 3] |= (uint8_t)(1 << (dx & 7));
        }
      }
    }
  }
  #undef FWD_ON
}

static void prv_draw_icon(GContext *ctx, GRect bounds, int offset_x, bool has_fwd, const uint8_t *fwd_icon_bytes, GDrawCommandImage *pdc, int index, int scale_pct) {
  GPoint offset = GPoint(offset_x, 0);
  if (has_fwd && fwd_icon_bytes) {
    if (s_forwarded_icon) {
      int bmp_scale = (scale_pct > 100) ? 100 : scale_pct;
      prv_render_forwarded_icon(s_forwarded_icon, fwd_icon_bytes, bmp_scale);
      graphics_context_set_compositing_mode(ctx, GCompOpAssign);
      GSize isz = gbitmap_get_bounds(s_forwarded_icon).size;
      GRect dest = GRect(offset.x + bounds.origin.x + (bounds.size.w - isz.w) / 2,
                         bounds.origin.y + (bounds.size.h - isz.h) / 2,
                         isz.w, isz.h);
      graphics_draw_bitmap_in_rect(ctx, s_forwarded_icon, dest);
    }
  } else if (pdc) {
    GPoint pdc_offset = GPoint(bounds.origin.x + offset.x, bounds.origin.y + offset.y);
    gdraw_command_image_draw(ctx, pdc, pdc_offset);
  } else {
    int cx = bounds.origin.x + offset.x + bounds.size.w / 2;
    int cy = bounds.origin.y + bounds.size.h / 2;
    int sw = bounds.size.w * scale_pct / 100;
    int sh = bounds.size.h * scale_pct / 100;
    GRect scaled_bounds = GRect(cx - sw / 2, cy - sh / 2, sw, sh);
    prv_draw_procedural_arrow(ctx, scaled_bounds, index);
  }
}

static void prv_stop_icon_anim(void) {
  if (s_anim_timer) {
    app_timer_cancel(s_anim_timer);
    s_anim_timer = NULL;
  }
  
  if (s_anim_state == ANIM_STATE_TRANSITIONING) {
#if Steer_Has_Transitions
    // Clean up previous transition states
    if (s_prev_pdc_image) {
      gdraw_command_image_destroy(s_prev_pdc_image);
      s_prev_pdc_image = NULL;
    }
#endif
  }
  s_anim_state = ANIM_STATE_IDLE;
}

#if Steer_Has_Transitions
// Maps a maneuver index to the side the new card should enter from.
//  - Cancel (-1) and left-hand maneuvers slide in from the left.
//  - U-turns and arrive/depart slide up from the bottom.
//  - Everything else (straight, right, ferry) slides in from the right.
static SlideDirection prv_slide_dir_for_maneuver(int idx) {
  if (idx == -1) {
    return SLIDE_DIR_LEFT_TO_RIGHT;  // pop / cancel
  }
  switch (idx) {
    // Left-hand maneuvers: LEFT(9), roundabout-left exits(13-20), 29/31, SLIGHT_LEFT(33),
    // KEEP_LEFT(39), RAMP_LEFT(11).
    case 9: case 11:
    case 13: case 14: case 15: case 16: case 17: case 18: case 19: case 20:
    case 29: case 31: case 33: case 39:
      return SLIDE_DIR_LEFT_TO_RIGHT;
    // U-turn(36/37) and arrive/depart(0/1/2/3): enter from the bottom.
    case 0: case 1: case 2: case 3: case 36: case 37:
      return SLIDE_DIR_BOTTOM_TO_TOP;
    default:
      return SLIDE_DIR_RIGHT_TO_LEFT;
  }
}

static void prv_anim_timer_cb(void *ctx) {
  s_anim_timer = NULL;

  if (s_anim_state == ANIM_STATE_TRANSITIONING) {
    s_anim_pct += 10; // increment progress by 10% (10 frames)
#if USE_CARDS_TRANSITION
    if (s_anim_pct == 50) {
      if (s_prev_pdc_image) {
        gdraw_command_image_destroy(s_prev_pdc_image);
        s_prev_pdc_image = NULL;
      }
      prv_icon_snapshot_points();
    }
    
    if (s_anim_pct >= 100) {
      s_anim_pct = 100;
      s_anim_state = ANIM_STATE_IDLE;
      if (s_active_pdc_image) {
        prv_icon_apply_morph(s_active_pdc_image, 0);
      }
      if (s_prev_pdc_image) {
        gdraw_command_image_destroy(s_prev_pdc_image);
        s_prev_pdc_image = NULL;
      }
    } else {
      s_anim_timer = app_timer_register(16, prv_anim_timer_cb, NULL);
    }
#else
    if (s_anim_pct >= 100) {
      s_anim_pct = 100;
      s_anim_state = ANIM_STATE_IDLE;

      // Settle the icon back to its undeformed geometry.
      if (s_active_pdc_image) {
        prv_icon_apply_scale(s_active_pdc_image, 100, 100, 100, 100);
      }
      // Clean up previous transition states
      if (s_prev_pdc_image) {
        gdraw_command_image_destroy(s_prev_pdc_image);
        s_prev_pdc_image = NULL;
      }
    } else {
      // Squash & stretch the entering vector icon along the slide axis. The table is
      // {width%, height%}; for a vertical slide we swap the axes so it stretches in Y.
      int frame_idx = s_anim_pct / 10;
      if (frame_idx < 0) frame_idx = 0;
      if (frame_idx > MOOOK_FRAMES) frame_idx = MOOOK_FRAMES;
      int32_t fx = s_moook_squash_stretch[frame_idx][0];
      int32_t fy = s_moook_squash_stretch[frame_idx][1];
      if (s_anim_slide_dir == SLIDE_DIR_BOTTOM_TO_TOP) {
        int32_t tmp = fx; fx = fy; fy = tmp;
      }
      if (s_active_pdc_image) {
        prv_icon_apply_scale(s_active_pdc_image, 100, 100, fx, fy);
      }
      s_anim_timer = app_timer_register(25, prv_anim_timer_cb, NULL);
    }
#endif

    if (s_panel_layer) {
      layer_mark_dirty(s_panel_layer);
    }
  }
}
#endif

static void prv_update_ui(void) {
  if (s_status_bar_layer) {
    layer_mark_dirty(s_status_bar_layer);
  }

  bool page_changed = (s_maneuver_index != s_anim_prev_maneuver) || 
                      (s_has_forwarded_icon != s_last_rendered_has_forwarded) ||
                      (strcmp(s_street_text, s_last_rendered_street) != 0);

#if !Steer_Has_Transitions
  if (page_changed) {
    s_anim_prev_maneuver = s_maneuver_index;
    s_last_rendered_has_forwarded = s_has_forwarded_icon;
    s_last_rendered_bg_color = s_bg_color;
    strncpy(s_last_rendered_street, s_street_text, sizeof(s_last_rendered_street));
    strncpy(s_last_rendered_distance, s_distance_text, sizeof(s_last_rendered_distance));
    
    s_anim_state = ANIM_STATE_IDLE;
    s_anim_pct = 100;
    s_forwarded_icon_dirty = false;
    
    if (s_active_pdc_image) {
      gdraw_command_image_destroy(s_active_pdc_image);
      s_active_pdc_image = NULL;
    }
    
    if (s_maneuver_index >= 0 && s_maneuver_index < 41) {
      s_active_pdc_image = gdraw_command_image_create_with_resource(s_pdc_resource_ids[s_maneuver_index]);
      if (s_active_pdc_image) {
        prv_icon_compute_fit();
        prv_icon_scale_once(s_active_pdc_image);
      }
    }
    if (s_panel_layer) {
      layer_mark_dirty(s_panel_layer);
    }
  } else {
    bool distance_changed = strcmp(s_distance_text, s_last_rendered_distance) != 0;
    if (distance_changed || s_forwarded_icon_dirty) {
      strncpy(s_last_rendered_distance, s_distance_text, sizeof(s_last_rendered_distance));
      s_forwarded_icon_dirty = false;
      if (s_panel_layer) {
        layer_mark_dirty(s_panel_layer);
      }
    }
  }
#else
  if (page_changed) {
    if (s_anim_prev_maneuver == -2) {
      // First run: no animation, just set up active state immediately
      s_anim_prev_maneuver = s_maneuver_index;
      s_last_rendered_has_forwarded = s_has_forwarded_icon;
      s_last_rendered_bg_color = s_bg_color;
      strncpy(s_last_rendered_street, s_street_text, sizeof(s_last_rendered_street));
      strncpy(s_last_rendered_distance, s_distance_text, sizeof(s_last_rendered_distance));
      
      s_anim_state = ANIM_STATE_IDLE;
      s_anim_pct = 100;
      s_forwarded_icon_dirty = false;
      
      if (s_maneuver_index >= 0 && s_maneuver_index < 41) {
        s_active_pdc_image = gdraw_command_image_create_with_resource(s_pdc_resource_ids[s_maneuver_index]);
        if (s_active_pdc_image) {
          prv_icon_compute_fit();
#if USE_CARDS_TRANSITION
          prv_icon_scale_once(s_active_pdc_image);
          prv_icon_snapshot_points();
#else
          prv_icon_snapshot_points();
          prv_icon_apply_scale(s_active_pdc_image, 100, 100, 100, 100);
#endif
        }
      }
    } else {
      // Transitioning!
      prv_stop_icon_anim();
#if defined(PBL_COLOR)
      // The panel slide owns this change; cancel any in-flight digit morph
      prv_dg_stop();
#endif
      s_prev_pdc_image = s_active_pdc_image;
      s_prev_maneuver_index = s_anim_prev_maneuver;
      s_prev_has_forwarded = s_last_rendered_has_forwarded;
      s_prev_bg_color = s_last_rendered_bg_color;
      strncpy(s_prev_distance_text, s_last_rendered_distance, sizeof(s_prev_distance_text));
      strncpy(s_prev_street_text, s_last_rendered_street, sizeof(s_prev_street_text));
      
      s_active_pdc_image = NULL;
      
      // Load new active state
      if (s_maneuver_index >= 0 && s_maneuver_index < 41) {
        s_active_pdc_image = gdraw_command_image_create_with_resource(s_pdc_resource_ids[s_maneuver_index]);
        if (s_active_pdc_image) {
          prv_icon_compute_fit();
#if USE_CARDS_TRANSITION
          prv_icon_scale_once(s_active_pdc_image);
          // Snapshot points is deferred to s_anim_pct == 50 in prv_anim_timer_cb!
#else
          prv_icon_snapshot_points();
          prv_icon_apply_scale(s_active_pdc_image, 100, 100, 100, 100);
#endif
        }
      }
      
      // Contextual slide direction: the card enters from the side that matches the
      // maneuver (left turns from the left, u-turns/arrive/depart from the bottom).
      s_anim_slide_dir = prv_slide_dir_for_maneuver(s_maneuver_index);
      
      // Update stable trackers to new active values
      s_anim_prev_maneuver = s_maneuver_index;
      s_last_rendered_has_forwarded = s_has_forwarded_icon;
      s_last_rendered_bg_color = s_bg_color;
      strncpy(s_last_rendered_street, s_street_text, sizeof(s_last_rendered_street));
      strncpy(s_last_rendered_distance, s_distance_text, sizeof(s_last_rendered_distance));
      
      s_forwarded_icon_dirty = false;
      s_anim_state = ANIM_STATE_TRANSITIONING;
      s_anim_pct = 0;
#if USE_CARDS_TRANSITION
      s_anim_timer = app_timer_register(16, prv_anim_timer_cb, NULL);
#else
      s_anim_timer = app_timer_register(25, prv_anim_timer_cb, NULL);
#endif
    }
    
    if (s_panel_layer) {
      layer_mark_dirty(s_panel_layer);
    }
  } else {
    // Page did not change, check if distance changed or forwarded icon is dirty
    bool distance_changed = strcmp(s_distance_text, s_last_rendered_distance) != 0;
    if (distance_changed || s_forwarded_icon_dirty) {
#if defined(PBL_COLOR)
      // Kick off the per-digit squash morph from the old value to the new one. Captures
      // the previous distance BEFORE it is overwritten below.
      if (distance_changed) prv_dg_start(s_last_rendered_distance, s_distance_text);
#endif
      strncpy(s_last_rendered_distance, s_distance_text, sizeof(s_last_rendered_distance));
      s_forwarded_icon_dirty = false;
      if (s_panel_layer) {
        layer_mark_dirty(s_panel_layer);
      }
    }
  }
#endif

  if (s_speed_sign_layer) {
    layer_set_hidden(s_speed_sign_layer, !s_speed_alert_active);
    layer_mark_dirty(s_speed_sign_layer);
  }

  prv_update_backlight();
}

// AppMessage Callback Handlers
// Fired when no nav message has arrived for IDLE_TIMEOUT_MS — leave the app (back to watchface).
static void prv_idle_timeout_cb(void *data) {
  s_idle_timer = NULL;
  APP_LOG(APP_LOG_LEVEL_INFO, "Idle timeout (no nav for 2 min) - closing app");
  window_stack_pop_all(true);
}

// (Re)arm the idle timer. Called on every inbound message and at startup; any activity
// pushes the auto-close back out by another IDLE_TIMEOUT_MS.
static void prv_reset_idle_timer(void) {
  if (s_idle_timer) {
    app_timer_reschedule(s_idle_timer, IDLE_TIMEOUT_MS);
  } else {
    s_idle_timer = app_timer_register(IDLE_TIMEOUT_MS, prv_idle_timeout_cb, NULL);
  }
}

static void inbox_received_handler(DictionaryIterator *iterator, void *context) {
  APP_LOG(APP_LOG_LEVEL_INFO, "AppMessage received!");

  // Any inbound message counts as activity — push the auto-close timeout back out.
  prv_reset_idle_timer();
  
  // Debug print all received tuples
  Tuple *t = dict_read_first(iterator);
  while (t) {
    APP_LOG(APP_LOG_LEVEL_DEBUG, "  Tuple: key=%d, type=%d, len=%d", (int)t->key, (int)t->type, (int)t->length);
    t = dict_read_next(iterator);
  }

  bool needs_update = false;

  // Check instruction text
  Tuple *text_t = dict_find(iterator, MESSAGE_KEY_NAV_TEXT);
  if (text_t && text_t->type == TUPLE_CSTRING) {
    char *separator = strstr(text_t->value->cstring, " \xE2\x80\x94 "); // Space + em-dash (UTF-8 E2 80 94) + Space
    if (separator) {
      int dist_len = separator - text_t->value->cstring;
      if (dist_len >= (int)sizeof(s_distance_text)) {
        dist_len = sizeof(s_distance_text) - 1;
      }
      strncpy(s_distance_text, text_t->value->cstring, dist_len);
      s_distance_text[dist_len] = '\0';
      
      snprintf(s_street_text, sizeof(s_street_text), "%s", separator + 5); // Skip em-dash separator (5 bytes total)
    } else {
      s_distance_text[0] = '\0';
      snprintf(s_street_text, sizeof(s_street_text), "%s", text_t->value->cstring);
    }
    needs_update = true;
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed Distance: %s, Street: %s", s_distance_text, s_street_text);
  }

  // Check turn index
  Tuple *turn_t = dict_find(iterator, MESSAGE_KEY_NAV_TURN);
  if (turn_t) {
    int32_t new_maneuver = turn_t->value->int32;
    if (new_maneuver != s_maneuver_index) {
      if (new_maneuver >= 0 && s_vibe_on_turn) {
        vibes_double_pulse();
      }
      s_maneuver_index = new_maneuver;
      needs_update = true;
      APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_TURN: %d", s_maneuver_index);
    }
  }

  // Check invert color (theme)
  Tuple *invert_t = dict_find(iterator, MESSAGE_KEY_NAV_INVERT_COLOR);
  if (invert_t) {
    bool new_invert = (invert_t->value->int32 != 0);
    if (new_invert != s_invert_color) {
      s_invert_color = new_invert;
      needs_update = true;
      APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_INVERT_COLOR: %d", s_invert_color);
    }
  }

  // Check GPS status
  Tuple *gps_t = dict_find(iterator, MESSAGE_KEY_NAV_GPS_ACCURACY);
  if (gps_t && gps_t->type == TUPLE_CSTRING) {
    snprintf(s_gps_text, sizeof(s_gps_text), "GPS: %s", gps_t->value->cstring);
    needs_update = true;
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_GPS_ACCURACY: %s", gps_t->value->cstring);
  }

  // Check ETA
  Tuple *eta_t = dict_find(iterator, MESSAGE_KEY_NAV_ETA);
  if (eta_t && eta_t->type == TUPLE_CSTRING) {
    snprintf(s_eta_text, sizeof(s_eta_text), "ETA: %s", eta_t->value->cstring);
    needs_update = true;
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_ETA: %s", eta_t->value->cstring);
  }

  // Check forwarded icon bitmap
  Tuple *icon_t = dict_find(iterator, MESSAGE_KEY_NAV_ICON_BITMAP);
  if (icon_t && icon_t->type == TUPLE_BYTE_ARRAY && icon_t->length == 384) {
#if !defined(PBL_PLATFORM_APLITE)
    memcpy(s_forwarded_icon_bytes, icon_t->value->data, 384);
    s_has_forwarded_icon = true;
    s_forwarded_icon_dirty = true;
#else
    s_has_forwarded_icon = false;
#endif
    needs_update = true;
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_ICON_BITMAP.");
  } else {
    if (dict_find(iterator, MESSAGE_KEY_NAV_TURN) || dict_find(iterator, MESSAGE_KEY_NAV_TEXT)) {
      s_has_forwarded_icon = false;
      needs_update = true;
    }
  }

  // Check background color
  Tuple *bg_color_t = dict_find(iterator, MESSAGE_KEY_NAV_BG_COLOR);
  if (bg_color_t) {
    uint32_t val = bg_color_t->value->uint32;
    uint8_t r = (val >> 16) & 0xFF;
    uint8_t g = (val >> 8) & 0xFF;
    uint8_t b = val & 0xFF;
    s_bg_color = GColorFromRGB(r, g, b);
    needs_update = true;
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_BG_COLOR: %lx -> RGB(%d,%d,%d)", (unsigned long)val, r, g, b);
  }

  // Check vibration on turn settings
  Tuple *vibe_t = dict_find(iterator, MESSAGE_KEY_NAV_VIBE_ON_TURN);
  if (vibe_t) {
    s_vibe_on_turn = (vibe_t->value->uint8 != 0);
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_VIBE_ON_TURN: %d", s_vibe_on_turn);
  }

  // Check speed warning alert
  Tuple *speed_alert_t = dict_find(iterator, MESSAGE_KEY_NAV_SPEED_ALERT);
  Tuple *speed_limit_t = dict_find(iterator, MESSAGE_KEY_NAV_SPEED_LIMIT);
  if (speed_limit_t) {
    s_speed_limit = speed_limit_t->value->uint8;
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_SPEED_LIMIT: %d", s_speed_limit);
  }
  if (speed_alert_t) {
    bool new_alert = (speed_alert_t->value->uint8 != 0);
    if (new_alert != s_speed_alert_active) {
      s_speed_alert_active = new_alert;
      if (s_speed_alert_active) {
        vibes_long_pulse();
      }
      needs_update = true;
      APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_SPEED_ALERT: %d", s_speed_alert_active);
    }
  }

  // Current speed (km/h) for the speedometer. Stored now; drawn once STEER_SHOW_SPEEDOMETER
  // is enabled (layout TBD).
  Tuple *speed_t = dict_find(iterator, MESSAGE_KEY_NAV_SPEED);
  if (speed_t) {
    s_current_speed = speed_t->value->uint8;
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_SPEED: %d km/h", s_current_speed);
#if STEER_SHOW_SPEEDOMETER
    needs_update = true;
#endif
  }

  // Check favorites list properties
  Tuple *fav_count_t = dict_find(iterator, MESSAGE_KEY_NAV_FAV_COUNT);
  if (fav_count_t) {
    s_fav_count = fav_count_t->value->uint8;
    if (s_fav_count > 5) s_fav_count = 5;
    for (int i = 0; i < 5; i++) {
      s_favorites[i].active = false;
      s_favorites[i].icon = 0;
    }
    persist_write_int(PERSIST_KEY_FAV_COUNT, s_fav_count);
    persist_write_data(PERSIST_KEY_FAVORITES, s_favorites, sizeof(s_favorites));
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_FAV_COUNT: %d", s_fav_count);
  }

  Tuple *fav_index_t = dict_find(iterator, MESSAGE_KEY_NAV_FAV_INDEX);
  Tuple *fav_name_t = dict_find(iterator, MESSAGE_KEY_NAV_FAV_NAME);
  if (fav_index_t && fav_name_t && fav_name_t->type == TUPLE_CSTRING) {
    uint8_t idx = fav_index_t->value->uint8;
    if (idx < 5) {
      strncpy(s_favorites[idx].name, fav_name_t->value->cstring, sizeof(s_favorites[idx].name) - 1);
      s_favorites[idx].name[sizeof(s_favorites[idx].name) - 1] = '\0';
      s_favorites[idx].active = true;
      
      Tuple *fav_icon_t = dict_find(iterator, MESSAGE_KEY_NAV_FAV_ICON);
      if (fav_icon_t) {
        s_favorites[idx].icon = fav_icon_t->value->uint8;
      } else {
        s_favorites[idx].icon = 0;
      }
      
      persist_write_data(PERSIST_KEY_FAVORITES, s_favorites, sizeof(s_favorites));
      APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed favorite %d: %s, icon: %d", idx, s_favorites[idx].name, s_favorites[idx].icon);
      
      if (s_favorites_window && s_favorites_menu_layer) {
        menu_layer_reload_data(s_favorites_menu_layer);
      }
    }
  }

  // Check if navigation is cancelled
  Tuple *cancel_t = dict_find(iterator, MESSAGE_KEY_NAV_CANCEL);
  if (cancel_t) {
    s_maneuver_index = -1;
    s_distance_text[0] = '\0';
    prv_set_waiting_text();
    snprintf(s_eta_text, sizeof(s_eta_text), "ETA: --:--");
    s_bg_color = NAV_SCREEN_BG;
    s_has_forwarded_icon = false;
    s_speed_alert_active = false;
    s_current_speed = -1;
    needs_update = true;
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Parsed NAV_CANCEL.");
  }

  // If we received NAV_TEXT_END or general properties that updated the state, redraw
  if (needs_update || dict_find(iterator, MESSAGE_KEY_NAV_TEXT_END)) {
    prv_update_ui();
  }
}

static void inbox_dropped_handler(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "Inbox message dropped! Reason: %d", reason);
}

static void outbox_failed_handler(DictionaryIterator *iterator, AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "Outbox send failed! Reason: %d", reason);
}

static void outbox_sent_handler(DictionaryIterator *iterator, void *context) {
  APP_LOG(APP_LOG_LEVEL_INFO, "Outbox send success!");
}

// Click Handlers (Back to exit and notify companion, Select to manually invert theme for testing)
static void prv_back_click_handler(ClickRecognizerRef recognizer, void *context) {
  APP_LOG(APP_LOG_LEVEL_INFO, "Back button clicked - sending NAV_CANCEL");
  
  // Send cancel command to phone
  DictionaryIterator *iter;
  app_message_outbox_begin(&iter);
  if (iter) {
    dict_write_uint8(iter, MESSAGE_KEY_NAV_CANCEL, 1);
    app_message_outbox_send();
  }
  
  // Pop the window to exit the app
  window_stack_pop(true);
}

static int16_t prv_get_cell_height(MenuLayer *menu_layer, MenuIndex *cell_index, void *context);

static void prv_fav_menu_select_callback(int index, void *context) {
  if (s_fav_count == 0) {
    return;
  }
  APP_LOG(APP_LOG_LEVEL_INFO, "Selected favorite index: %d (%s)", index, s_favorites[index].name);
  
  s_pending_fav_index = index;
  
  if (s_mode_window) {
    window_stack_push(s_mode_window, true);
  }
}

static uint16_t prv_mode_get_num_rows(MenuLayer *menu_layer, uint16_t section_index, void *context) {
  return 4;
}

static void prv_mode_draw_row(GContext *ctx, const Layer *cell_layer, MenuIndex *cell_index, void *context) {
  GRect bounds = layer_get_bounds(cell_layer);
  bool is_highlighted = menu_cell_layer_is_highlighted(cell_layer);
  
  GColor bg_resolved = prv_resolve_bg_color(s_bg_color);
  GColor fg_color = prv_distance_fg_for_bg(bg_resolved);
  
  if (is_highlighted) {
    graphics_context_set_fill_color(ctx, fg_color);
    graphics_fill_rect(ctx, bounds, 0, GCornerNone);
    graphics_context_set_text_color(ctx, bg_resolved);
  } else {
    graphics_context_set_fill_color(ctx, bg_resolved);
    graphics_fill_rect(ctx, bounds, 0, GCornerNone);
    graphics_context_set_text_color(ctx, fg_color);
  }
  
  const char *text = "";
  uint32_t res_id = 0;
  
  switch (cell_index->row) {
    case 0:
      text = prv_tr("Car", "Carro");
      res_id = RESOURCE_ID_IMAGE_MODE_CAR;
      break;
    case 1:
      text = prv_tr("Bicycle", "Bicicleta");
      res_id = RESOURCE_ID_IMAGE_MODE_BIKE;
      break;
    case 2:
      text = prv_tr("Walk", "A pé");
      res_id = RESOURCE_ID_IMAGE_MODE_WALK;
      break;
    case 3:
      text = prv_tr("Transit", "Transporte");
      res_id = RESOURCE_ID_IMAGE_MODE_TRANSIT;
      break;
  }
  
  GRect text_bounds = GRect(40, (bounds.size.h - 26) / 2, bounds.size.w - 50, 26);
  
  // Draw icon
  GBitmap *bmp = gbitmap_create_with_resource(res_id);
  if (bmp) {
    graphics_context_set_compositing_mode(ctx, GCompOpSet);
    graphics_draw_bitmap_in_rect(ctx, bmp, GRect(8, 9, 25, 25));
    gbitmap_destroy(bmp);
  }
  
  GFont font = fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
  graphics_draw_text(ctx, text, font, text_bounds, GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
}

static void prv_mode_select_click(MenuLayer *menu_layer, MenuIndex *cell_index, void *context) {
  uint8_t mode_index = cell_index->row;
  
  DictionaryIterator *iter;
  app_message_outbox_begin(&iter);
  if (iter) {
    dict_write_uint8(iter, MESSAGE_KEY_NAV_TRIGGER_ROUTE, s_pending_fav_index);
    dict_write_uint8(iter, MESSAGE_KEY_NAV_ROUTE_MODE, mode_index);
    app_message_outbox_send();
  }
  
  snprintf(s_street_text, sizeof(s_street_text), "%s",
           prv_tr("Starting route...", "Iniciando rota..."));
  prv_update_ui();
  
  window_stack_pop(false); // Pop mode picker window
  window_stack_pop(true);  // Pop favorites window with animation
}

static void prv_mode_window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(window_layer);
  
  s_mode_menu_layer = menu_layer_create(bounds);
  menu_layer_set_callbacks(s_mode_menu_layer, NULL, (MenuLayerCallbacks) {
    .get_num_rows = prv_mode_get_num_rows,
    .get_cell_height = prv_get_cell_height,
    .draw_row = prv_mode_draw_row,
    .select_click = prv_mode_select_click,
  });
  
  GColor bg_resolved = prv_resolve_bg_color(s_bg_color);
  GColor fg_color = prv_distance_fg_for_bg(bg_resolved);
  menu_layer_set_normal_colors(s_mode_menu_layer, bg_resolved, fg_color);
  menu_layer_set_highlight_colors(s_mode_menu_layer, fg_color, bg_resolved);
  
  menu_layer_set_click_config_onto_window(s_mode_menu_layer, window);
  layer_add_child(window_layer, menu_layer_get_layer(s_mode_menu_layer));
}

static void prv_mode_window_unload(Window *window) {
  menu_layer_destroy(s_mode_menu_layer);
  s_mode_menu_layer = NULL;
}

static uint16_t prv_get_num_rows(MenuLayer *menu_layer, uint16_t section_index, void *context) {
  return s_fav_count == 0 ? 1 : s_fav_count;
}

static int16_t prv_get_cell_height(MenuLayer *menu_layer, MenuIndex *cell_index, void *context) {
  return 44;
}

static uint32_t prv_get_fav_resource_id(uint8_t index) {
  if (index > 105) {
    index = 0;
  }
  return RESOURCE_ID_IMAGE_FAV_0 + index;
}

static void prv_draw_menu_row(GContext *ctx, const Layer *cell_layer, MenuIndex *cell_index, void *context) {
  GRect bounds = layer_get_bounds(cell_layer);
  bool is_highlighted = menu_cell_layer_is_highlighted(cell_layer);
  
  GColor bg_resolved = prv_resolve_bg_color(s_bg_color);
  GColor fg_color = prv_distance_fg_for_bg(bg_resolved);
  
  if (is_highlighted) {
    graphics_context_set_fill_color(ctx, fg_color);
    graphics_fill_rect(ctx, bounds, 0, GCornerNone);
    graphics_context_set_text_color(ctx, bg_resolved);
  } else {
    graphics_context_set_fill_color(ctx, bg_resolved);
    graphics_fill_rect(ctx, bounds, 0, GCornerNone);
    graphics_context_set_text_color(ctx, fg_color);
  }
  
  char text[64];
  GRect text_bounds;
  if (s_fav_count == 0) {
    snprintf(text, sizeof(text), "%s", prv_tr("No favourites", "Sem favoritos"));
    text_bounds = GRect(10, (bounds.size.h - 26) / 2, bounds.size.w - 20, 26);
  } else {
    snprintf(text, sizeof(text), "%s", s_favorites[cell_index->row].name);
    text_bounds = GRect(40, (bounds.size.h - 26) / 2, bounds.size.w - 50, 26);
    
    // Draw favorite icon
    GBitmap *bmp = gbitmap_create_with_resource(prv_get_fav_resource_id(s_favorites[cell_index->row].icon));
    if (bmp) {
      graphics_context_set_compositing_mode(ctx, GCompOpSet);
      graphics_draw_bitmap_in_rect(ctx, bmp, GRect(8, 9, 25, 25));
      gbitmap_destroy(bmp);
    }
  }
  
  GFont font = fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
  graphics_draw_text(ctx, text, font, text_bounds, GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
}

static void prv_menu_select_click(MenuLayer *menu_layer, MenuIndex *cell_index, void *context) {
  prv_fav_menu_select_callback(cell_index->row, context);
}

static void prv_favorites_window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(window_layer);
  
  s_favorites_menu_layer = menu_layer_create(bounds);
  menu_layer_set_callbacks(s_favorites_menu_layer, NULL, (MenuLayerCallbacks) {
    .get_num_rows = prv_get_num_rows,
    .get_cell_height = prv_get_cell_height,
    .draw_row = prv_draw_menu_row,
    .select_click = prv_menu_select_click,
  });
  
  GColor bg_resolved = prv_resolve_bg_color(s_bg_color);
  GColor fg_color = prv_distance_fg_for_bg(bg_resolved);
  menu_layer_set_normal_colors(s_favorites_menu_layer, bg_resolved, fg_color);
  menu_layer_set_highlight_colors(s_favorites_menu_layer, fg_color, bg_resolved);
  
  menu_layer_set_click_config_onto_window(s_favorites_menu_layer, window);
  layer_add_child(window_layer, menu_layer_get_layer(s_favorites_menu_layer));
}

static void prv_favorites_window_unload(Window *window) {
  menu_layer_destroy(s_favorites_menu_layer);
  s_favorites_menu_layer = NULL;
}

static void prv_show_favorites_menu(void) {
  if (s_favorites_window) {
    window_stack_push(s_favorites_window, true);
  }
}

static void prv_select_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (s_maneuver_index >= 0) {
    APP_LOG(APP_LOG_LEVEL_INFO, "Select button clicked - toggling backlight mode");
    s_backlight_always_on = !s_backlight_always_on;
    prv_update_ui();
  } else {
    APP_LOG(APP_LOG_LEVEL_INFO, "Select button clicked - opening favorites menu");
    prv_show_favorites_menu();
  }
}

static void prv_click_config_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_BACK, prv_back_click_handler);
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click_handler);
}

// Window Load/Unload
static void prv_window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);

  // Set the update procedure on the root window layer to draw the backgrounds
  layer_set_update_proc(window_layer, prv_bg_update_proc);

  // 1. Create Status Bar Layer
  s_status_bar_layer = layer_create(STATUS_BAR_RECT);
  layer_set_update_proc(s_status_bar_layer, prv_status_bar_update_proc);
  layer_add_child(window_layer, s_status_bar_layer);

  // 2. Create Unified Panel Layer
  GRect bounds = layer_get_bounds(window_layer);
  s_panel_layer = layer_create(GRect(0, STATUS_BAR_HEIGHT, bounds.size.w, bounds.size.h - STATUS_BAR_HEIGHT));
  layer_set_update_proc(s_panel_layer, prv_panel_update_proc);
  layer_add_child(window_layer, s_panel_layer);

  // 3. Create Speed Sign Layer (covers entire window)
  s_speed_sign_layer = layer_create(bounds);
  layer_set_update_proc(s_speed_sign_layer, prv_speed_sign_update_proc);
  layer_add_child(window_layer, s_speed_sign_layer);
  layer_set_hidden(s_speed_sign_layer, true); // Hidden by default

  // Allocate s_forwarded_icon once at target size
  int dim = 50;
#if defined(PBL_PLATFORM_EMERY)
  dim = 80;
#elif defined(PBL_PLATFORM_CHALK)
  dim = 60;
#endif
  s_forwarded_icon = gbitmap_create_blank(GSize(dim, dim), GBitmapFormat1Bit);

  // Load + fit the Steer waiting-screen branding icon (car rental).
  prv_wait_icon_init();

  // 7. Update elements with initial state
  s_anim_prev_maneuver = -2;  // force the first maneuver to animate
  prv_update_ui();
}

static void prv_window_unload(Window *window) {
  prv_stop_icon_anim();
#if defined(PBL_COLOR)
  prv_dg_stop();
#endif

  // Reset backlight state when unloading the window
  if (s_backlight_forced_on) {
    light_enable(false);
    s_backlight_forced_on = false;
  }
#if defined(PBL_PLATFORM_EMERY)
  light_set_color(GColorWhite);
#endif

  layer_destroy(s_panel_layer);
  layer_destroy(s_status_bar_layer);
  layer_destroy(s_speed_sign_layer);

  if (s_forwarded_icon) {
    gbitmap_destroy(s_forwarded_icon);
    s_forwarded_icon = NULL;
  }

  if (s_active_pdc_image) {
    gdraw_command_image_destroy(s_active_pdc_image);
    s_active_pdc_image = NULL;
  }

#if Steer_Has_Transitions
  if (s_prev_pdc_image) {
    gdraw_command_image_destroy(s_prev_pdc_image);
    s_prev_pdc_image = NULL;
  }
#endif

  if (s_wait_pdc_image) {
    gdraw_command_image_destroy(s_wait_pdc_image);
    s_wait_pdc_image = NULL;
  }
}

static void prv_init(void) {
  // Load persisted favorites if they exist
  if (persist_exists(PERSIST_KEY_FAV_COUNT)) {
    s_fav_count = persist_read_int(PERSIST_KEY_FAV_COUNT);
  }
  if (persist_exists(PERSIST_KEY_FAVORITES)) {
    int read_bytes = persist_read_data(PERSIST_KEY_FAVORITES, s_favorites, sizeof(s_favorites));
    if (read_bytes < (int)sizeof(s_favorites)) {
      for (int i = 0; i < 5; i++) {
        s_favorites[i].icon = 0;
      }
    }
  }

  s_bg_color = NAV_SCREEN_BG;
  s_last_rendered_bg_color = NAV_SCREEN_BG;
  s_window = window_create();
  window_set_click_config_provider(s_window, prv_click_config_provider);
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });

  s_favorites_window = window_create();
  window_set_window_handlers(s_favorites_window, (WindowHandlers) {
    .load = prv_favorites_window_load,
    .unload = prv_favorites_window_unload,
  });

  s_mode_window = window_create();
  window_set_window_handlers(s_mode_window, (WindowHandlers) {
    .load = prv_mode_window_load,
    .unload = prv_mode_window_unload,
  });

  // Open AppMessage inbox and outbox
  app_message_register_inbox_received(inbox_received_handler);
  app_message_register_inbox_dropped(inbox_dropped_handler);
  app_message_register_outbox_failed(outbox_failed_handler);
  app_message_register_outbox_sent(outbox_sent_handler);
  
  app_message_open(1024, 64);

  // Send request for favorites to Android companion on startup
  DictionaryIterator *iter;
  app_message_outbox_begin(&iter);
  if (iter) {
    dict_write_uint8(iter, MESSAGE_KEY_NAV_REQUEST_FAVS, 1);
    app_message_outbox_send();
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Sent NAV_REQUEST_FAVS (key 16) to phone");
  }

  // Subscribe to minute tick service for the status bar clock
  tick_timer_service_subscribe(MINUTE_UNIT, prv_tick_handler);

  // Localize the initial placeholder text now that we can read the system locale.
  prv_set_waiting_text();

  const bool animated = true;
  window_stack_push(s_window, animated);

  // Start the inactivity countdown: if the app is opened but no nav ever arrives, it
  // closes itself after IDLE_TIMEOUT_MS instead of lingering on the watch.
  prv_reset_idle_timer();
}

static void prv_deinit(void) {
  if (s_idle_timer) {
    app_timer_cancel(s_idle_timer);
    s_idle_timer = NULL;
  }
  tick_timer_service_unsubscribe();
  window_destroy(s_window);
  if (s_favorites_window) {
    window_destroy(s_favorites_window);
  }
  if (s_mode_window) {
    window_destroy(s_mode_window);
  }
}

int main(void) {
  prv_init();
  APP_LOG(APP_LOG_LEVEL_DEBUG, "NavMe initialized.");
  app_event_loop();
  prv_deinit();
}
