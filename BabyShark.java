/**
 * TAC Supply Chain Management Simulator
 * http://www.sics.se/tac/    tac-dev@sics.se
 * <p>
 * Copyright (c) 2001-2003 SICS AB. All rights reserved.
 * <p>
 * SICS grants you the right to use, modify, and redistribute this
 * software for noncommercial purposes, on the conditions that you:
 * (1) retain the original headers, including the copyright notice and
 * this text, (2) clearly document the difference between any derived
 * software and the original, and (3) acknowledge your use of this
 * software in pertaining publications and reports.  SICS provides
 * this software "as is", without any warranty of any kind.  IN NO
 * EVENT SHALL SICS BE LIABLE FOR ANY DIRECT, SPECIAL OR INDIRECT,
 * PUNITIVE, INCIDENTAL OR CONSEQUENTIAL LOSSES OR DAMAGES ARISING OUT
 * OF THE USE OF THE SOFTWARE.
 * <p>
 * -----------------------------------------------------------------
 * <p>
 * ExampleAgent
 * <p>
 * Author  : Joakim Eriksson, Niclas Finne, Sverker Janson
 * Created : Tue May 06 17:41:55 2003
 * Updated : $Date: 2005/06/08 22:34:39 $
 * $Revision: 1.13 $
 */

import java.util.Random;
import java.util.logging.Logger;

import se.sics.tasim.props.BOMBundle;
import se.sics.tasim.props.ComponentCatalog;
import se.sics.tasim.props.InventoryStatus;
import se.sics.tasim.props.OfferBundle;
import se.sics.tasim.props.OrderBundle;
import se.sics.tasim.props.RFQBundle;
import se.sics.tasim.props.SimulationStatus;
import se.sics.tasim.props.StartInfo;
import se.sics.tasim.tac03.aw.Order;
import se.sics.tasim.tac03.aw.OrderStore;
import se.sics.tasim.tac03.aw.RFQStore;
import se.sics.tasim.tac03.aw.SCMAgent;


public class BabyShark extends SCMAgent {

    private static final Logger log =
            Logger.getLogger(ExampleAgent.class.getName());

    private Random random = new Random();

    /**
     * Latest possible due date when bidding for customer orders
     */
    private int lastBidDueDate;

    /**
     * Offer price discount factor when bidding for customer orders
     */
    private double priceDiscountFactor = 0.2;

    /**
     * Bookkeeper for component demand for accepted customer orders
     */
    private InventoryStatus componentDemand = new InventoryStatus();

    public BabyShark() {
    }

    /**
     * Called when the agent received all startup information and it is
     * time to start participating in the simulation.
     */
    protected void simulationStarted() {
        StartInfo info = getStartInfo();
        this.lastBidDueDate = info.getNumberOfDays() - 2;
    }

    /**
     * Called when a game/simulation has ended and the agent should
     * free its resources.
     */
    protected void simulationEnded() {
    }

    /**
     * Called when a bundle of RFQs have been received from the
     * customers. In TAC03 SCM the customers only send one bundle per
     * day and the same RFQs are sent to all manufacturers.
     *
     * @param rfqBundle a bundle of RFQs
     */
    protected void handleCustomerRFQs(RFQBundle rfqBundle) {
        int currentDate = getCurrentDate();
        for (int i = 0, n = rfqBundle.size(); i < n; i++) {
            int dueDate = rfqBundle.getDueDate(i);
            if ((dueDate - currentDate) >= 6 && (dueDate <= lastBidDueDate)) {
                int resPrice = rfqBundle.getReservePricePerUnit(i);

                int quantity = rfqBundle.getQuantity(i);
                double penalty = rfqBundle.getPenalty(i);
                int productId = rfqBundle.getProductID(i);
                BOMBundle bomBundle = getBOMBundle();
                int[] components = bomBundle.getComponentsForProductID(productId);
                int cost = bomBundle.getProductBasePrice(productId - 1);
                int offeredPrice = (int) (resPrice * (1.0 - random.nextDouble() * priceDiscountFactor));
                double profit = (offeredPrice - cost) * quantity;
                if ((profit / penalty) > 0.1) {
                    addCustomerOffer(rfqBundle, i, offeredPrice);
                }
            }
        }
        // Finished adding offers. Send all offers to the customers.
        sendCustomerOffers();
    }

    /**
     * Called when a bundle of orders have been received from the
     * customers. In TAC03 SCM the customers only send one order bundle
     * per day as response to offers (and only if they want to order
     * something).
     *
     * @param newOrders the new customer orders
     */
    protected void handleCustomerOrders(Order[] newOrders) {
        BOMBundle bomBundle = getBOMBundle();
        for (int i = 0, n = newOrders.length; i < n; i++) {
            Order order = newOrders[i];
            int productID = order.getProductID();
            int quantity = order.getQuantity();
            int[] components = bomBundle.getComponentsForProductID(productID);
            if (components != null) {
                for (int j = 0, m = components.length; j < m; j++) {
                    componentDemand.addInventory(components[j], quantity);
                }
            }
        }

        ComponentCatalog catalog = getComponentCatalog();
        int currentDate = getCurrentDate();
        for (int i = 0, n = componentDemand.getProductCount(); i < n; i++) {
            int quantity = componentDemand.getQuantity(i);
            if (quantity > 0) {
                int productID = componentDemand.getProductID(i);
                String[] suppliers = catalog.getSuppliersForProduct(productID);
                if (suppliers != null) {
                    for (int j = 0; j < suppliers.length; j++) {
                        addSupplierRFQ(suppliers[j], productID, quantity, 0, currentDate + 2);
                        componentDemand.addInventory(productID, -quantity);
                    }
                } else {
                    // There should always be suppliers for all components so
                    // this point should never be reached.
                    log.severe("no suppliers for product " + productID);
                }
            }
        }
        sendSupplierRFQs();
    }

    /**
     * Called when a bundle of offers have been received from a
     * supplier. In TAC03 SCM suppliers only send on offer bundle per
     * day in reply to RFQs (and only if they had something to offer).
     *
     * @param supplierAddress the supplier that sent the offers
     * @param offers          a bundle of offers
     */
    protected void handleSupplierOffers(String supplierAddress,
                                        OfferBundle offers) {
        // Earliest complete is always after partial offers so the offer
        // bundle is traversed backwards to always accept earliest offer
        // instead of the partial (the server will ignore the second
        // order for the same offer).
        for (int i = offers.size() - 1; i >= 0; i--) {
            // Only order if quantity > 0 (otherwise it is only a price quote)
            if (offers.getQuantity(i) > 0) {
                addSupplierOrder(supplierAddress, offers, i);
            }
        }
        sendSupplierOrders();
    }

    /**
     * Called when a simulation status has been received and that all
     * messages from the server this day have been received. The next
     * message will be for the next day.
     *
     * @param status a simulation status
     */
    protected synchronized void handleSimulationStatus(SimulationStatus status) {

        // The inventory for next day is calculated with todays deliveries
        // and production and is changed when production and delivery
        // requests are made.
        InventoryStatus inventory = getInventoryForNextDay();

        // Generate production and delivery schedules
        int currentDate = getCurrentDate();
        int latestDueDate = currentDate - getDaysBeforeVoid() + 2;

        OrderStore customerOrders = getCustomerOrders();
        Order[] orders = customerOrders.getActiveOrders();
        if (orders != null) {
            for (int i = 0, n = orders.length; i < n; i++) {
                Order order = orders[i];
                int productID = order.getProductID();
                int dueDate = order.getDueDate();
                int orderedQuantity = order.getQuantity();
                int inventoryQuantity = inventory.getInventoryQuantity(productID);

                if ((currentDate >= (dueDate - 1)) && (dueDate >= latestDueDate)
                        && addDeliveryRequest(order)) {
                    // It was time to deliver this order and it could be
                    // delivered (the method above ensures this). The order has
                    // automatically been marked as delivered and the products
                    // have been removed from the inventory status (to avoid
                    // delivering the same products again).

                } else if (dueDate <= latestDueDate) {

                    // It is too late to produce and deliver this order
                    log.info("canceling to late order " + order.getOrderID()
                            + " (dueDate=" + order.getDueDate()
                            + ",date=" + currentDate + ')');
                    cancelCustomerOrder(order);

                } else if (inventoryQuantity >= orderedQuantity) {

                    // There is enough products in the inventory to fulfill this
                    // order and nothing more should be produced for it. However
                    // to avoid reusing these products for another order they
                    // must be reserved.
                    reserveInventoryForNextDay(productID, orderedQuantity);

                } else if (addProductionRequest(productID,
                        orderedQuantity - inventoryQuantity)) {
                    // The method above will ensure that the needed components
                    // was available and that the factory had enough free
                    // capacity. It also removed the needed components from the
                    // inventory status.

                    // Any existing products have been allocated to this order
                    // and must be reserved to avoid using them in another
                    // production or delivery.
                    reserveInventoryForNextDay(productID, inventoryQuantity);

                } else {
                    // Otherwise the production could not be done (lack of
                    // free factory cycles or not enough components in
                    // inventory) and nothing can be done for this order at
                    // this time.
                }
            }
        }

        sendFactorySchedules();
    }

    private void cancelCustomerOrder(Order order) {
        order.setCanceled();

        // The components for the canceled order are now available to be
        // used in other orders.
        int[] components =
                getBOMBundle().getComponentsForProductID(order.getProductID());
        if (components != null) {
            int quantity = order.getQuantity();
            for (int j = 0, m = components.length; j < m; j++) {
                componentDemand.addInventory(components[j], -quantity);
            }
        }
    }

}
