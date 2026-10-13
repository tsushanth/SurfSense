// Decides when the linking-code poll should stop: only when MORE devices are linked than when the
// code was generated (a user who already has a linked device must not lose the code after 3 s).
export function shouldStopPolling(initialCount, currentCount) {
  return currentCount > initialCount;
}
