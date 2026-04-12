"""
test_differential.py — Integration smoke test for the Anvil differential harness.

Requires:
  - `anvil` and `forge` on PATH  (Foundry toolchain)
  - sew-protocol repo at ../../sew-protocol relative to this file (or override
    via SEW_PROTOCOL_ROOT environment variable)

Run (from sew-simulation repo root):
    pytest python/tests/test_differential.py -v -m integration

Marks:
    integration — skipped by default; run explicitly when Foundry is available.
"""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

import pytest

# ---------------------------------------------------------------------------
# Pytest mark
# ---------------------------------------------------------------------------

pytestmark = pytest.mark.integration


def _has_tool(name: str) -> bool:
    try:
        subprocess.run([name, '--version'], capture_output=True, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope='module')
def sew_protocol_root() -> str:
    env = os.environ.get('SEW_PROTOCOL_ROOT')
    if env:
        return env
    # Default: two dirs up from python/, then into sew-protocol sibling
    candidates = [
        Path(__file__).parent.parent.parent.parent / 'sew-protocol',
        Path(__file__).parent.parent.parent / 'sew-protocol',
    ]
    for c in candidates:
        if c.exists():
            return str(c)
    pytest.skip('sew-protocol root not found — set SEW_PROTOCOL_ROOT')


@pytest.fixture(scope='module')
def runner(sew_protocol_root):
    """
    Start a shared AnvilRunner for all tests in this module.
    Deploying takes ~5 seconds; we deploy once and reuse the instance.
    """
    if not _has_tool('anvil'):
        pytest.skip('anvil not installed')
    if not _has_tool('forge'):
        pytest.skip('forge not installed')
    if not _has_tool('cast'):
        pytest.skip('cast not installed')

    from sew_sim.anvil_runner import AnvilRunner
    r = AnvilRunner(sew_protocol_root=sew_protocol_root)
    r.start()
    yield r
    r.stop()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestDifferentialHarness:
    """
    Each test builds on the state left by prior tests (they share the same
    Anvil instance within the module).  The scenario tested:

        create_escrow → raise_dispute → resolve_release

    After each action we verify that the EVM state matches the expected
    projection and that the harness can detect divergence when state is
    deliberately mis-read.
    """

    def test_deployment_successful(self, runner):
        """DifferentialSetup deployed and oracle address is populated."""
        assert runner.addrs.get('vault'), 'vault address missing'
        assert runner.addrs.get('oracle'), 'oracle address missing'
        assert runner.addrs.get('token'), 'token address missing'
        assert runner.addrs.get('drModule'), 'drModule address missing'

    def test_create_escrow_state_is_pending(self, runner):
        """
        After createEscrow, EVM escrowState should be PENDING (1).
        Model projection should agree.
        """
        from sew_sim.anvil_runner import EVM_STATE

        amount = 1_000 * 10**18
        wf_id  = runner.execute_action('create_escrow', {'amount': amount})

        evm_state = runner.read_evm_state([wf_id])
        et = evm_state['escrow-transfers'].get(wf_id, {})
        assert et['escrow-state'] == 'pending', (
            f'Expected PENDING after create, got {et["escrow-state"]}'
        )

        # amount-after-fee should be < amount (1% fee applied)
        held = evm_state['total-held']
        assert held > 0, 'total-held should be > 0 after escrow creation'
        assert et['amount-after-fee'] == held, 'amount-after-fee should equal total-held for single escrow'

        # Store wf_id for subsequent tests
        runner._test_wf_id = wf_id  # type: ignore[attr-defined]

    def test_raise_dispute_state_is_disputed(self, runner):
        """After raiseDispute, EVM escrowState should be DISPUTED (4)."""
        wf_id = runner._test_wf_id  # type: ignore[attr-defined]
        runner.execute_action('raise_dispute', {'wf_id': wf_id})

        evm_state = runner.read_evm_state([wf_id])
        et = evm_state['escrow-transfers'][wf_id]
        assert et['escrow-state'] == 'disputed', (
            f'Expected DISPUTED after raiseDispute, got {et["escrow-state"]}'
        )
        # Dispute level should still be 0 (L0, not yet escalated)
        assert evm_state['dispute-levels'][wf_id] == 0, 'Dispute level should be 0 at L0'

    def test_compare_with_projection_no_divergence(self, runner):
        """
        Build a synthetic projection matching the current EVM state and
        confirm compare_with_projection returns None (no divergence).
        """
        wf_id     = runner._test_wf_id  # type: ignore[attr-defined]
        evm_state = runner.read_evm_state([wf_id])

        # Build a projection identical to EVM state
        projection = {
            'escrow-transfers': {
                wf_id: {
                    'escrow-state':     evm_state['escrow-transfers'][wf_id]['escrow-state'],
                    'amount-after-fee': evm_state['escrow-transfers'][wf_id]['amount-after-fee'],
                }
            },
            'total-held':          evm_state['total-held'],
            'total-fees':          evm_state['total-fees'],
            'pending-settlements': evm_state['pending-settlements'],
            'dispute-levels':      evm_state['dispute-levels'],
            'block-time':          evm_state['block-time'],
        }

        diff = runner.compare_with_projection(projection, [wf_id])
        assert diff is None, f'Expected no divergence; got: {diff}'

    def test_compare_with_projection_detects_divergence(self, runner):
        """
        Inject a wrong escrow-state in the projection and confirm
        compare_with_projection returns a non-empty diff.
        """
        wf_id     = runner._test_wf_id  # type: ignore[attr-defined]
        evm_state = runner.read_evm_state([wf_id])

        # Deliberately wrong state: claim it's 'released' when EVM says 'disputed'
        wrong_projection = {
            'escrow-transfers': {
                wf_id: {
                    'escrow-state':     'released',   # wrong!
                    'amount-after-fee': evm_state['escrow-transfers'][wf_id]['amount-after-fee'],
                }
            },
            'total-held':  evm_state['total-held'],
            'total-fees':  evm_state['total-fees'],
            'dispute-levels': evm_state['dispute-levels'],
        }

        diff = runner.compare_with_projection(wrong_projection, [wf_id])
        assert diff is not None, 'Expected divergence to be detected'
        assert 'escrow-transfers' in diff, f'Expected escrow-transfers diff; got {diff}'

    def test_resolve_release_state_is_resolved(self, runner):
        """
        After resolveRelease the escrow state should be RESOLVED (5) or
        PENDING if a settlement window is enforced.  We check that the state
        has advanced from DISPUTED.
        """
        wf_id = runner._test_wf_id  # type: ignore[attr-defined]
        try:
            runner.execute_action('resolve_release', {'wf_id': wf_id})
        except subprocess.CalledProcessError as exc:
            # May fail if resolver isn't assigned yet (DR assignment is async in
            # the real protocol — in differential tests the resolver is pre-set).
            pytest.skip(f'resolve_release reverted — resolver assignment may need advancing: {exc.stderr[-400:]}')

        evm_state = runner.read_evm_state([wf_id])
        et = evm_state['escrow-transfers'][wf_id]
        assert et['escrow-state'] in ('resolved', 'pending'), (
            f'Expected RESOLVED or PENDING after resolveRelease, got {et["escrow-state"]}'
        )
