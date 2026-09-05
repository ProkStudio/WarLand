package ru.warland.ui;

import java.util.*;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.*;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.*;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import ru.warland.api.WarLandApi.MenuEntry;

public final class Menus {
 private Menus(){}
 public static ItemStack icon(Item item,String title,String... lines){ItemStack s=new ItemStack(item);s.set(DataComponentTypes.CUSTOM_NAME,Text.literal(title).formatted(Formatting.AQUA).styled(st->st.withItalic(false)));s.set(DataComponentTypes.LORE,new LoreComponent(Arrays.stream(lines).map(t->(Text)Text.literal(t).formatted(Formatting.GRAY).styled(st->st.withItalic(false))).toList()));return s;}
 public static void open(ServerPlayerEntity p,String title,List<MenuEntry> entries){
  List<MenuEntry> safe=List.copyOf(entries.stream().limit(45).toList());SimpleInventory inv=new SimpleInventory(54);
  ItemStack filler=icon(Items.GRAY_STAINED_GLASS_PANE," ");for(int i=0;i<54;i++)inv.setStack(i,filler.copy());for(int i=0;i<safe.size();i++)inv.setStack(i,safe.get(i).icon().copy());inv.setStack(49,icon(Items.BARRIER,"Закрыть"));
  p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id,pi,who)->new ReadOnlyMenu(id,pi,inv,safe,p.getUuid()),Text.literal("WarLand • "+title)));
 }
 private static final class ReadOnlyMenu extends GenericContainerScreenHandler {
  private final List<MenuEntry> entries;private final UUID owner;private boolean clicked;
  ReadOnlyMenu(int id,PlayerInventory pi,SimpleInventory inv,List<MenuEntry> entries,UUID owner){super(ScreenHandlerType.GENERIC_9X6,id,pi,inv,6);this.entries=entries;this.owner=owner;}
  @Override public boolean canUse(PlayerEntity p){return p.getUuid().equals(owner);}
  @Override public ItemStack quickMove(PlayerEntity p,int slot){return ItemStack.EMPTY;}
  @Override public void onSlotClick(int slot,int button,SlotActionType action,PlayerEntity who){
   if(!(who instanceof ServerPlayerEntity p)||!canUse(p)||clicked)return;
   if(action!=SlotActionType.PICKUP||button!=0)return;
   if(slot==49){p.closeHandledScreen();return;}
   if(slot<0||slot>=entries.size())return;
   clicked=true;p.closeHandledScreen();entries.get(slot).action().run();
  }
 }
}
