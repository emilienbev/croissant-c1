# Calendar

<p align="center">
  <img alt="The calendar: today's team standup at 9:30 in grey as it has ended, the dentist at 14:00 in violet and a dinner reservation at 20:00, then the gym and a book club tomorrow, and a Lisbon trip all day on Friday 30 October" src="../images/features/calendar-agenda.png" width="300">
  <img alt="An event's page: the dentist, today from 14:00 to 14:45, in 1 h 50 min in violet, then where and notes, over the red X" src="../images/features/calendar-event.png" width="300">
</p>

Your events from today on.

- There's no add key. Hold the side button and say it: "the dentist on Monday at two".
- Only days with something on show, under today, tomorrow or the date. What has ended today is grey, and the list opens on the next event.
- An event's page shows when it is, how long until it starts if that's today, and its place and notes. Tap the title to rename it. The red X deletes it after a confirm.
- Home shows today's next two events.

Ask uses one `agenda_add` tool for events and [reminders](reminders.md), and the date decides which you get. A time makes a one-hour event, a day alone an all-day one, and no day or time a reminder.

| You say | Ask calls | You get |
|---|---|---|
| "remind me to buy oat milk" | `agenda_add {"title": "buy oat milk"}` | a reminder |
| "remind me to call the bank tomorrow" | `agenda_add {"title": "call the bank", "at": "2026-10-30"}` | an all-day event |
| "the dentist on Monday at two" | `agenda_add {"title": "dentist", "at": "2026-11-02T14:00:00+01:00"}` | a one-hour event |

Ask also moves an event, gives it an end, a place or notes, deletes it, and tells you what's coming up.
