package dev.monocle.client.gui.widgets.pressable;

/** A button disabled between press and release must not execute a stale action. */
public final class WPressableTest {
    public static void run() {
        class Button extends WPressable { void arm() { pressed=true; } }
        Button button=new Button();int[] calls={0};button.action=()->calls[0]++;
        button.arm();button.disabled=true;
        assert !button.onMouseReleased(null);
        assert !button.onMouseClicked(null,false);
        assert calls[0]==0;
        class Confirmed extends WConfirmedButton {
            Confirmed(){super("Cancel","Really?",null);}
            void arm(){pressed=pressedOnce=true;}
        }
        Confirmed confirmed=new Confirmed();confirmed.action=()->calls[0]++;
        confirmed.arm();confirmed.disabled=true;
        assert !confirmed.onMouseReleased(null);
        assert confirmed.getText().equals("Cancel") && calls[0]==0;
    }
}
