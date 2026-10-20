//! LOCAL PATCH: an optional early transition point for each play request.

#[derive(Default)]
pub(crate) struct AutoDj {
    request: Option<u64>,
    range: Option<(u8, u8)>,
    percent: Option<u8>,
    armed: bool,
}

impl AutoDj {
    pub(crate) fn transition_at(
        &mut self,
        request: u64,
        duration_ms: u32,
        fade_ms: u32,
        position_ms: u32,
        range: Option<(u8, u8)>,
        choose: impl FnOnce(u8, u8) -> u8,
    ) -> Option<u32> {
        if self.request != Some(request) || self.range != range {
            *self = Self {
                request: Some(request),
                range,
                ..Self::default()
            };
        }
        let (min, max) = range?;
        if min < 50 || max > 95 || min > max || duration_ms < 90_000 || fade_ms == 0 {
            return None;
        }
        let percent = *self.percent.get_or_insert_with(|| choose(min, max));
        let target = ((u64::from(duration_ms) * u64::from(percent) / 100) as u32)
            .min(duration_ms.saturating_sub(fade_ms));
        if target == 0 {
            return None;
        }
        // Enabling or resuming beyond the point leaves this song playing.
        // A seek back before it arms the transition again.
        if position_ms < target {
            self.armed = true;
        }
        self.armed.then_some(target)
    }
}

#[cfg(test)]
mod tests {
    use super::AutoDj;

    #[test]
    fn picks_once_per_play_request_and_keeps_the_full_fade() {
        let mut dj = AutoDj::default();
        assert_eq!(
            dj.transition_at(1, 240_000, 6_000, 0, Some((65, 85)), |_, _| 75),
            Some(180_000)
        );
        assert_eq!(
            dj.transition_at(1, 240_000, 6_000, 180_000, Some((65, 85)), |_, _| panic!(
                "redrawn"
            )),
            Some(180_000)
        );
        assert_eq!(
            dj.transition_at(2, 240_000, 6_000, 0, Some((65, 85)), |_, _| 80),
            Some(192_000)
        );
    }

    #[test]
    fn enabling_late_does_not_skip_and_seeking_back_can_arm() {
        let mut dj = AutoDj::default();
        assert_eq!(
            dj.transition_at(1, 240_000, 6_000, 200_000, Some((75, 75)), |min, _| min),
            None
        );
        assert_eq!(
            dj.transition_at(1, 240_000, 6_000, 100_000, Some((75, 75)), |_, _| panic!(
                "redrawn"
            )),
            Some(180_000)
        );
    }

    #[test]
    fn short_tracks_disabled_modes_and_invalid_ranges_do_not_schedule() {
        for (duration, fade, range) in [
            (89_999, 6_000, Some((65, 85))),
            (240_000, 0, Some((65, 85))),
            (240_000, 6_000, None),
            (240_000, 6_000, Some((85, 65))),
            (240_000, 6_000, Some((0, 200))),
        ] {
            assert_eq!(
                AutoDj::default()
                    .transition_at(1, duration, fade, 0, range, |_, _| panic!("ineligible")),
                None
            );
        }
    }

    #[test]
    fn changing_settings_rearms_only_before_the_new_target() {
        let mut dj = AutoDj::default();
        assert_eq!(
            dj.transition_at(1, 240_000, 6_000, 0, Some((65, 85)), |_, _| 85),
            Some(204_000)
        );
        assert_eq!(
            dj.transition_at(1, 240_000, 6_000, 190_000, Some((75, 75)), |min, _| min),
            None
        );
        assert_eq!(
            dj.transition_at(1, 240_000, 6_000, 190_000, None, |_, _| panic!("disabled")),
            None
        );
    }
}
